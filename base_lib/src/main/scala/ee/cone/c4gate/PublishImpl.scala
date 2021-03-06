package ee.cone.c4gate

import java.nio.file._
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.{N_Header, S_Manifest}
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala

//todo un-publish

import scala.jdk.CollectionConverters.IterableHasAsScala

@c4assemble("PublishingCompApp") class PublishingAssembleBase(publishing: Publishing){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(
      WithPK(PublishingFromFilesTx()(publishing)),
      WithPK(PublishingFromStringsTx()(publishing)),
    )
}

case class PublishingFromFilesTx(srcId: SrcId="PublishingFromFilesTx")(publishing: Publishing) extends TxTransform {
  def transform(local: Context): Context = publishing.checkPublishFromFiles(local)
}
case class PublishingFromStringsTx(srcId: SrcId="PublishingFromStringsTx")(publishing: Publishing) extends TxTransform {
  def transform(local: Context): Context = publishing.checkPublishFromStrings(local)
}

trait PublicDirProvider {
  def get: List[(String,Path)]
}

@c4("PublishingCompApp") final class ModPublicDirProvider(config: ListConfig) extends PublicDirProvider {
  def get: List[(String,Path)] = {
    val Mod = """.+/mod\.([^/]+)\.(classes|jar)""".r
    val hasMod = (for {
      classpath <- config.get("CLASSPATH")
      Mod(m,_) <- classpath.split(":")
    } yield m).toSet
    // println(s"hasMod: $hasMod")
    val Line = """(\S+)\s+(\S+)\s+(\S+)""".r
    for {
      mainPublicPath <- "htdocs" :: config.get("C4GENERATOR_MAIN_PUBLIC_PATH")
      fName <- Option(Paths.get(mainPublicPath).resolve("c4gen.ht.links")).toList if Files.exists(fName)
      Line(mod,url,pf) <- Files.readAllLines(fName).asScala.toList if hasMod(mod)
    } yield (url,Paths.get(s"$mainPublicPath/$pf"))
  }
}

// we need it, because there's no good place to found there's new *.svg in src/
case object InitialPublishDone extends TransientLens[Boolean](false)

@c4("PublishingCompApp") final class Publishing(
  idGenUtil: IdGenUtil,
  publishFromStringsProviders: List[PublishFromStringsProvider],
  mimeTypesProviders: List[PublishMimeTypesProvider],
  publicDirProviders: List[PublicDirProvider],
  publishFullCompressor: PublishFullCompressor,
  publisher: Publisher,
  txAdd: LTxAdd,
)(
  mimeTypes: String=>Option[String] = mimeTypesProviders.flatMap(_.get).toMap.get,
  compressor: Compressor = publishFullCompressor.value
) extends LazyLogging {
  def checkPublishFromStrings(local: Context): Context = {
    val strEvents = publisher.publish("FromStrings", for {
      publishFromStringsProvider <- publishFromStringsProviders
      (path,body) <- publishFromStringsProvider.get
    } yield prepare(path,ToByteString(body)))(local)
    txAdd.add(strEvents).andThen(SleepUntilKey.set(Instant.MAX))(local)
  }
  def checkPublishFromFiles(local: Context): Context = //Seq[Observer[RichContext]]
    if(!InitialPublishDone.of(local))
      txAdd.add(publishFromFiles(local)).andThen(InitialPublishDone.set(true))(local)
    else {
      val timeToPublish =
        List(Paths.get("htdocs/publish_time")).filter(Files.exists(_))
          .flatMap(path=>publisher.publish("FromFilesTime",List(prepare("/publish_time",ToByteString(Files.readAllBytes(path)))))(local))
      if(timeToPublish.isEmpty)
        SleepUntilKey.set(Instant.ofEpochMilli(System.currentTimeMillis+1000))(local)
      else txAdd.add(publishFromFiles(local) ++ timeToPublish)(local)
    }
  def publishFromFiles: Context=>Seq[LEvent[Product]] =
    publisher.publish("FromFiles", for {
      publicDirProvider <- publicDirProviders
      (url,file) <- publicDirProvider.get
    } yield prepare(url,ToByteString(Files.readAllBytes(file))))
  def prepare(path: String, body: ByteString): ByPathHttpPublication = {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) "" else path.substring(pointPos+1)
    val byteString = compressor.compress(body)
    val mimeType = mimeTypes(ext)
    val eTag = "v1-" +
      idGenUtil.srcIdFromSerialized(0,byteString) +
      idGenUtil.srcIdFromSerialized(0,ToByteString(s"${mimeType.getOrElse("")}:"))
    val headers =
      N_Header("etag", s""""$eTag"""") ::
        N_Header("content-encoding", compressor.name) ::
        mimeType.map(N_Header("content-type",_)).toList
    ByPathHttpPublication(path,headers,byteString)
  }
}
