package verse.rates.processor

import org.json4s.JsonAST.{JObject, JField, JInt, JString}
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.writePretty
import spray.http._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._
import spray.http.MediaTypes._
import spray.http.HttpHeaders.RawHeader
import spray.routing.{RequestContext, Route, HttpService}
import spray.http.MediaTypes._
import verse.rates.model.MxSong
import verse.rates.model.VerseMetrics._
import verse.rates.processor.VectorsProcessor.TitleBox
import verse.rates.processor.VerseResponses.VectorsProcessorProvider

import scala.io.Source
import scala.util.{Failure, Try}

/** **
  *
  * ***/
object VerseResponses {
  trait VectorsProcessorProvider {
    val vectorsProcessor: VectorsProcessor
  }

  implicit val json4sFormats = Serialization.formats(NoTypeHints)

  val suggestLimit = 10
  val similarLimit = 6
}

abstract class VerseResponses extends HttpService with VectorsProcessorProvider {
  import VerseResponses._
  import VectorsProcessor._

  def respJsonString(json: RequestContext => String): Route =
    respJsonString(StatusCodes.OK)(json)

  def respJsonString(code: StatusCode)(json: RequestContext => String): Route = {
    respondWithMediaType(`application/json`) {
      respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*")) { ctx =>
        ctx.complete(HttpResponse(StatusCodes.OK,
          HttpEntity(ContentType(MediaTypes.`application/json`, HttpCharsets.`UTF-8`),
            json(ctx)
          )))
      }
    }
  }

  def respResourceExt(resourceName: String) = respJsonString { ctx =>
    Source.fromInputStream(getClass.getResourceAsStream(resourceName))
      .getLines
      .mkString("\n")
  }

  def respSimilar() = respJsonString { ctx =>
    val jsonBody = ctx.request.entity.asString
    val (songs, lang) = Try {
      val json = parse(jsonBody) \ "similar"
      val lang = json \ "lang" match {
        case JString("rus") => LangTag.Rus
        case _ => LangTag.Eng
      }

      val limit = json \ "limit" match {
        case JInt(i) => i.toInt
        case _ => similarLimit
      }

      val idValue = json \ "id"
      val rowsValue = json \ "rows"

      (idValue, rowsValue) match {
        case (JInt(id), JNothing) =>
          val iid = id.toInt
          vectorsProcessor.findSimilar(iid, limit+1).filter(_.id != iid) -> lang
        case (JNothing, JArray(arr)) =>
          val rowsSyls = arr.map { case jv =>

            println(jv)

            val JString(plain) = jv \ "plain"
            val syls = jv \ "syl" match {
              case JArray(sylArr) =>
                sylArr.map { sylVal =>
                  val JInt(pos) = sylVal \ "start"
                  val JInt(length) = sylVal \ "length"
                  val JString(sign) = sylVal \ "type"
                  Syllable(pos.toInt, length.toInt, sign match {
                    case "+" => AccentStressed
                    case "-" => AccentUnstressed
                    case _ => AccentAmbiguous
                  })
                }
              case JNothing =>
                Seq.empty[Syllable]
              case _ =>
                throw new IllegalArgumentException("Illegal syllables type.")
            }
            plain -> syls
          }
          vectorsProcessor.findSimilar(rowsSyls, limit) -> lang
        case _ =>
          throw new IllegalArgumentException("Illegal request.\n" + jsonBody)
      }
    } match {
      case scala.util.Success((sng, lng)) => sng -> lng
      case Failure(f) =>
        val msg = Option(f.getMessage).fold("")(" " + _)
        println(s"${f.getClass.getCanonicalName}.$msg")
        Seq.empty[MxSong] -> LangTag.Eng
    }
    writeSongs(songs, lang)
  }

  def respPresyllables() = respJsonString { ctx =>
    val jsonBody = ctx.request.entity.asString
    val syllabledRows = Try {
      val json = parse(jsonBody) \ "presyllables"

      json \ "rows" match {
        case arr: JArray =>
          val rows = arr.arr
            .toVector
            .zipWithIndex.map { case (js, idx) =>
            js match {
              case JString(s) => s
              case _ => throw new IllegalArgumentException(s"Illegal value at row $idx.")
            }
          }
          val rowsCalculated = vectorsProcessor.calcSyllables(rows)
          rowsCalculated
        case _ => throw new IllegalArgumentException("Illegal request.\n" + jsonBody)
      }
    } match {
      case scala.util.Success(sl) => sl
      case Failure(f) =>
        val msg = Option(f.getMessage).fold("")(" " + _)
        println(s"${f.getClass.getCanonicalName}.$msg")
        Seq.empty[(String, Syllables)]
    }
    writeSyllables(syllabledRows)
  }

  def respSongs() = respJsonString { ctx =>
    val jsonBody = ctx.request.entity.asString
    val (songs, lang) = Try {
      val json = parse(jsonBody) \ "byId"
      val lang = json \ "lang" match {
        case JString("rus") => LangTag.Rus
        case _ => LangTag.Eng
      }

      json \ "ids" match {
        case arr: JArray =>
          val ids = arr.arr.flatMap {
            case JInt(i) => Some(i.toInt)
            case _ => None
          }
          vectorsProcessor.byid(ids) -> lang

        case JInt(id) => vectorsProcessor.byid(Vector(id.toInt)) -> lang

        case _ => throw new IllegalArgumentException("Illegal request.\n" + jsonBody)
      }
    } match {
      case scala.util.Success(sl) => sl
      case Failure(f) =>
        val msg = Option(f.getMessage).fold("")(" " + _)
        println(s"${f.getClass.getCanonicalName}.$msg")
        Seq.empty[MxSong] -> LangTag.Eng
    }
    writeSongs(songs, lang)
  }

  private[this] def writeSongs(songs: Seq[MxSong], lang: LangTag): String = {
    val sLang = if (lang == LangTag.Rus) "rus" else "eng"
    val songsVal = JField("songs",
      songs.map { song => SongsBox.song2Json(song, lang) })

    val rootObj = JObject(
      JField("object", JString("frontend.songs.response")),
      JField("version", JString("1.0")),
      JField("lang", JString(sLang)),
      songsVal
    )
    writePretty(rootObj)
  }

  private[this] def syllable2Val(syl: Syllable): JObject = {
    JObject(
      JField("start", JInt(syl.pos)),
      JField("length", JInt(syl.len)),
      JField("type", JString(syl.accent match {
        case AccentStressed => "+"
        case AccentUnstressed => "-"
        case _ => "?"
      }))
    )
  }

  private[this] def writeSyllables(rows: Seq[(String, Syllables)]): String = {
    val rowsList = rows.toList
      .map { case (r, syls) =>
        val plain = JField("plain", JString(r))
        val fields =
          if (syls.isEmpty) plain :: Nil
          else plain :: JField("syl", JArray(syls.map(syllable2Val).toList)) :: Nil
        JObject(fields)
      }

    val rowsVal = JField("rows", JArray(rowsList))

    val rootObj = JObject(
      JField("object", JString("frontend.syllables.response")),
      JField("version", JString("1.0")),
      JField("syllables", JObject(rowsVal))
    )
    writePretty(rootObj)
  }

  private[this] def writeTitleBoxes(titles: Seq[TitleBox]): String = {
    val titlesVal = JField("titles",
      titles.map { tb =>
        JObject(
          JField("id", JInt(tb.id)),
          JField("title", JString(tb.title))
        )
      }
    )
    val rootObj = JObject(
      JField("object", JString("frontend.suggest.title.response")),
      JField("version", JString("1.0")),
      JField("lang", JString("eng")),
      titlesVal
    )
    writePretty(rootObj)
  }

  def respSuggestion(prefix: String, limit: Option[Int]) = respJsonString { ctx =>
    val titles = Try {
      vectorsProcessor.suggest(prefix, limit.getOrElse(suggestLimit))
    } match {
      case scala.util.Success(tbxs) => tbxs
      case Failure(_) =>
        println(s"Failed on vector producing [$prefix:$limit]")
        Seq.empty[TitleBox]
    }
    writeTitleBoxes(titles)
  }

  def respSuggestion() = respJsonString { ctx =>
    val jsonBody = ctx.request.entity.asString
    val titles = Try {
      val json = parse(jsonBody) \ "suggestTitle"
      (json \ "keywords", json \ "limit") match {
        case (JString(s), JInt(l)) => vectorsProcessor.suggest(s, l.toInt)
        case (JString(s), JNothing) => vectorsProcessor.suggest(s, suggestLimit)
        case _ => throw new IllegalArgumentException("Illegal request.\n" + jsonBody)
      }
    } match {
      case scala.util.Success(tt) => tt
      case Failure(f) =>
        val msg = Option(f.getMessage).fold("")(" " + _)
        println(s"${f.getClass.getCanonicalName}.$msg")
        Seq.empty[TitleBox]
    }
    writeTitleBoxes(titles)
  }
}
