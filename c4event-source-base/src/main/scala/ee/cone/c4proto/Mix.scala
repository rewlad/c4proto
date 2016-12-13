package ee.cone.c4proto


trait QMessagesApp extends ProtocolsApp {
  override def protocols: List[Protocol] = QProtocol :: super.protocols
  def rawQSender: RawQSender
  lazy val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  lazy val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  lazy val qMessageMapperFactory: QMessageMapperFactory =
    new QMessageMapperFactory(qAdapterRegistry, qMessages)
}

trait QReducerApp {
  def treeAssembler: TreeAssembler
  def qAdapterRegistry: QAdapterRegistry
  def qMessages: QMessages
  def qMessageMapperFactory: QMessageMapperFactory
  lazy val qReducerFactory: ReducerFactory =
    new ReducerFactoryImpl(qMessageMapperFactory, qMessages, treeAssembler)
}

trait ServerApp {
  def toStart: List[CanStart]
  lazy val serverFactory: ServerFactory = new ServerFactoryImpl
  lazy val execution: Executable = new ExecutionImpl(toStart)
}

////

trait TreeAssemblerApp extends DataDependenciesApp {
  def protocols: List[Protocol]
  lazy val indexFactory: IndexFactory = new IndexFactoryImpl
  override def dataDependencies: List[DataDependencyTo[_]] =
    ProtocolDataDependencies(protocols) ::: super.dataDependencies
  lazy val treeAssembler: TreeAssembler = TreeAssemblerImpl(dataDependencies)
}

////

trait ProtocolsApp {
  def protocols: List[Protocol] = Nil
}