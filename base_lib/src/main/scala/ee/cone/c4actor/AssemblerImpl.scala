package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4di.Types.ComponentFactory
import ee.cone.c4di.{c4, c4multi, provide}

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration

@c4("RichDataCompApp") final class ProtocolDataDependencies(qAdapterRegistry: QAdapterRegistry, origKeyFactory: OrigKeyFactoryFinalHolder) extends DataDependencyProvider {
  def getRules: List[WorldPartRule] =
    qAdapterRegistry.byId.values.map(_.className).toList.sorted
      .map(nm => new OriginalWorldPart(origKeyFactory.value.rawKey(nm)))
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

@c4("RichDataCompApp") final class DefLongAssembleWarnPeriod extends LongAssembleWarnPeriod(Option(System.getenv("C4ASSEMBLE_WARN_PERIOD_MS")).fold(1000L)(_.toLong))

@c4("RichDataCompApp") final class DefAssembleOptions extends AssembleOptions("AssembleOptions",false,0L)

@c4("RichDataCompApp") final class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: DeferredSeq[DataDependencyProvider],
  composes: IndexUtil,
  origKeyFactory: OrigKeyFactoryFinalHolder,
  assembleProfiler: AssembleProfiler,
  readModelUtil: ReadModelUtil,
  actorName: ActorName,
  updateProcessor: Option[UpdateProcessor],
  processors: List[UpdatesPreprocessor],
  defaultAssembleOptions: AssembleOptions,
  warnPeriod: LongAssembleWarnPeriod,
  catchNonFatal: CatchNonFatal,
  isTargetWorldPartRules: List[IsTargetWorldPartRule]
)(
  assembleOptionsOuterKey: AssembledKey = origKeyFactory.value.rawKey(classOf[AssembleOptions].getName),
  assembleOptionsInnerKey: String = ToPrimaryKey(defaultAssembleOptions)
) extends ToInject with LazyLogging {
  private def toTreeReplace(replace: Replace, isActiveOrig: Set[AssembledKey], assembled: ReadModel, updates: Seq[N_Update], profiling: JoiningProfiling, executionContext: OuterExecutionContext): Future[WorldTransition] = {
    val timer = NanoTimer()
    val mDiff = for {
      tpPair <- updates.groupBy(_.valueTypeId)
      (valueTypeId, tpUpdates) = tpPair : (Long,Seq[N_Update])
      valueAdapter <- qAdapterRegistry.byId.get(valueTypeId)
      wKey <- Option(origKeyFactory.value.rawKey(valueAdapter.className)) if isActiveOrig(wKey)
    } yield {
      implicit val ec: ExecutionContext = executionContext.value
      wKey -> (for {
        wasIndex <- wKey.of(assembled)
      } yield composes.mergeIndex(for {
        iPair <- tpUpdates.groupBy(_.srcId)
        (srcId, iUpdates) = iPair
        rawValue = iUpdates.last.value
        remove = composes.removingDiff(wasIndex,srcId)
        add = if(rawValue.size > 0) composes.result(srcId,valueAdapter.decode(rawValue),+1) :: Nil else Nil
        res <- remove :: add
      } yield res))
    }
    val diff = readModelUtil.create(mDiff.toMap)
    logger.trace(s"toTree ${timer.ms} ms")
    replace.replace(assembled,diff,profiling,executionContext)
  }

  def waitFor[T](res: Future[T], options: AssembleOptions, stage: String): T = concurrent.blocking{
    val end = NanoTimer()
    val result = Await.result(res, Duration.Inf)
    val period = end.ms
    if(period > warnPeriod.value) logger.warn(s"${options.toString} long join $period ms on $stage")
    result
  }

  // read model part:
  private def reduce(
    replace: Replace, isActiveOrig: Set[AssembledKey],
    wasAssembled: ReadModel, updates: Seq[N_Update],
    options: AssembleOptions, executionContext: OuterExecutionContext
  ): ReadModel = {
    val profiling = assembleProfiler.createJoiningProfiling(None)
    val res = toTreeReplace(replace, isActiveOrig, wasAssembled, updates, profiling, executionContext).map(_.result)(executionContext.value)
    waitFor(res, options, "read")
  }

  private def offset(events: Seq[RawEvent]): List[N_Update] = for{
    ev <- events.lastOption.toList
    lEvent <- LEvent.update(S_Offset(actorName.value,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  private def readModelAdd(replace: Replace, isActiveOrig: Set[AssembledKey], executionContext: OuterExecutionContext): Seq[RawEvent]=>ReadModel=>ReadModel = events => assembled => catchNonFatal {
    val options = getAssembleOptions(assembled)
    val updates = offset(events) ::: toUpdate.toUpdates(events.toList)
    reduce(replace, isActiveOrig, assembled, updates, options, executionContext)
  }("reduce"){ e => // ??? exception to record
      if(events.size == 1){
        val options = getAssembleOptions(assembled)
        val updates = offset(events) ++
          events.map(ev=>S_FailedUpdates(ev.srcId, e.getMessage))
            .flatMap(LEvent.update).map(toUpdate.toUpdate)
        reduce(replace, isActiveOrig, assembled, updates, options, executionContext)
      } else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(readModelAdd(replace, isActiveOrig, executionContext)(a), readModelAdd(replace, isActiveOrig, executionContext)(b)))(assembled)
      }
  }
  // other parts:
  private def add(out: Seq[N_Update]): Context => Context = {
    if (out.isEmpty) identity[Context]
    else { local =>
      implicit val executionContext: ExecutionContext = local.executionContext.value
      val options = getAssembleOptions(local.assembled)
      val processedOut = composes.mayBePar(processors, options).flatMap(_.process(out)).toSeq ++ out
      val externalOut = updateProcessor.fold(processedOut)(_.process(processedOut, WriteModelKey.of(local).size))
      val replace = TreeAssemblerKey.of(local)
      val isActiveOrig = IsActiveOrigKey.of(local)
      val profiling = assembleProfiler.createJoiningProfiling(Option(local))
      val res = for {
        transition <- toTreeReplace(replace, isActiveOrig, local.assembled, externalOut, profiling, local.executionContext)
        updates <- assembleProfiler.addMeta(transition, externalOut)
      } yield {
        val nLocal = new Context(local.injected, transition.result, local.executionContext, local.transient)
        WriteModelKey.modify(_.enqueueAll(updates))(nLocal)
      }
      waitFor(res, options, "add")
      //call add here for new mortal?
    }
  }

  private def getAssembleOptions(assembled: ReadModel): AssembleOptions = {
    val index = composes.getInstantly(assembleOptionsOuterKey.of(assembled))
    composes.getValues(index,assembleOptionsInnerKey,"").collectFirst{
      case o: AssembleOptions => o
    }.getOrElse(defaultAssembleOptions)
  }

  def toInject: List[Injectable] = {
    logger.debug("getDependencies started")
    val rules = getDependencies.value.flatMap(_.getRules).toList
    logger.debug("getDependencies finished")
    val isTargetWorldPartRule = Single.option(isTargetWorldPartRules).getOrElse(AnyIsTargetWorldPartRule)
    val replace = treeAssembler.create(rules,isTargetWorldPartRule.check)
    logger.debug(s"active rules: ${replace.active.size}")
    logger.debug{
      val isActive = replace.active.toSet
      rules.map{ rule =>
        s"\n${if(isActive(rule))"[+]" else "[-]"} ${rule match {
          case r: DataDependencyFrom[_] => s"${r.assembleName} ${r.name}"
          case r: DataDependencyTo[_] => s"out ${r.outputWorldKey}"
        }}"
      }.toString
    }
    val origKeys = replace.active.collect{ case o: OriginalWorldPart[_] => o.outputWorldKey }.toSet
      /*
      val reg = CheckedMap(
        replace.active.collect{
          case t: DataDependencyTo[_] => t.outputWorldKey
        }.collect{
          case k: JoinKey if keyFactory.rawKey(k.valueClassName)==k =>
            k.valueClassName -> k
        }
      )*/
    TreeAssemblerKey.set(replace) ::: IsActiveOrigKey.set(origKeys) :::
      WriteModelDebugAddKey.set(out => local =>
        if(out.isEmpty) local else {
          logger.debug(out.map(v=>s"\norig added: $v").mkString)
          add(out.map(toUpdate.toUpdate))(local)
        }
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(events=>context=>
        readModelAdd(replace, origKeys, context.executionContext)(events)(context.assembled)
      ) :::
      GetAssembleOptions.set(getAssembleOptions)
  }
}

abstract class IsTargetWorldPartRule {
  def check(rule: WorldPartRule): Boolean
}

object AnyIsTargetWorldPartRule extends IsTargetWorldPartRule {
  def check(rule: WorldPartRule): Boolean = true
}

case class UniqueIndexMap[K,V](index: Index)(indexUtil: IndexUtil) extends Map[K,V] {
  def updated[B1 >: V](k: K, v: B1): Map[K, B1] = iterator.toMap.updated(k,v)
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"")).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k => (k,Single(indexUtil.getValues(index,k,""))).asInstanceOf[(K,V)] }
  def removed(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
  override def keySet: Set[K] = indexUtil.keySet(index).asInstanceOf[Set[K]] // to get keys from index
}

@c4("RichDataCompApp") final class DynamicByPKImpl(indexUtil: IndexUtil) extends DynamicByPK {
  def get(joinKey: AssembledKey, context: AssembledContext): Map[SrcId,Product] = {
    val index: Index = indexUtil.getInstantly(joinKey.of(context.assembled))
    UniqueIndexMap(index)(indexUtil)
  }
}

@c4multi("RichDataCompApp") final case class GetByPKImpl[V<:Product](typeKey: TypeKey)(
  dynamic: DynamicByPK,
  needAssembledKeyRegistry: NeedAssembledKeyRegistry,
)(
  joinKey: AssembledKey = needAssembledKeyRegistry.toAssembleKey(typeKey),
) extends GetByPK[V] {
  def cl: Class[V] = typeKey.cl.asInstanceOf[Class[V]]
  def ofA(context: AssembledContext): Map[SrcId,V] =
    dynamic.get(joinKey,context).asInstanceOf[Map[SrcId,V]]
}

@c4("RichDataCompApp") final class GetByPKUtil(keyFactory: KeyFactory) {
  def toAssembleKey(vTypeKey: TypeKey): AssembledKey = {
    vTypeKey.args match {
      case Seq() =>
        keyFactory.rawKey(vTypeKey.clName)
      case Seq(arg) if arg.args.isEmpty =>
        keyFactory.rawKey(vTypeKey.clName + '[' + arg.clName + ']')
      case _ => throw new Exception(s"$vTypeKey not implemented") // todo: ? JoinKey to contain TypeKey-s
    }
  }
}
@c4("RichDataCompApp") final class GetByPKComponentFactoryProvider(
  getByPKImplFactory: GetByPKImplFactory
) {
  @provide def get: Seq[ComponentFactory[GetByPK[_]]] =
    List(args=>List(getByPKImplFactory.create(Single(args))))
}

@c4("RichDataCompApp") final class NeedAssembledKeyRegistry(
  util: GetByPKUtil, componentRegistry: ComponentRegistry, config: ListConfig,
)(
  disableCheck: Boolean = config.get("C4NO_INDEX_CHECK").nonEmpty
)(
  classNames: Set[String] = if(disableCheck) Set() else Set(classOf[GetByPK[_]].getName) // can be extended later
)(
  val getRules: List[NeedWorldPartRule] = for{
    component <- componentRegistry.components.toList
    inKey <- component.in if classNames(inKey.clName)
  } yield new NeedWorldPartRule(List(util.toAssembleKey(Single(inKey.args))), component.out.clName)
)(
  values: Set[AssembledKey] = getRules.flatMap(_.inputWorldKeys).toSet
) extends DataDependencyProvider {
  def toAssembleKey(typeKey: TypeKey): AssembledKey = {
    val joinKey = util.toAssembleKey(typeKey)
    assert(values(joinKey) || disableCheck, s"no need byPK self check: $joinKey")
    joinKey
  }
}

class NeedWorldPartRule(
  val inputWorldKeys: List[AssembledKey], val name: String
) extends WorldPartRule with DataDependencyFrom[Index] {
  def assembleName: String = "Tx"
}

@c4("SkipWorldPartsApp") final class IsTargetWorldPartRuleImpl extends IsTargetWorldPartRule {
  def check(rule: WorldPartRule): Boolean = rule.isInstanceOf[NeedWorldPartRule]
}
