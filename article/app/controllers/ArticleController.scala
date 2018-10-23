package controllers

import com.gu.contentapi.client.model.v1.{ItemResponse, Content => ApiContent}
import common._
import contentapi.ContentApiClient
import model.{ContentType, PageWithStoryPackage, _}
import pages.{ArticleEmailHtmlPage, ArticleHtmlPage}
import play.api.libs.ws.WSClient
import play.api.mvc._
import views.support._
import metrics.TimingMetric
import play.api.libs.json.Json
import renderers.RemoteRender
import services.CAPILookup
import implicits.{AmpFormat, EmailFormat, HtmlFormat, JsonFormat}
import model.Cached.{RevalidatableResult, WithoutRevalidationResult}
import model.dotcomponents.DotcomponentsDataModel
import services.dotcomponents._

import scala.concurrent.Future

case class ArticlePage(article: Article, related: RelatedContent) extends PageWithStoryPackage

class ArticleController(contentApiClient: ContentApiClient, val controllerComponents: ControllerComponents, ws: WSClient)(implicit context: ApplicationContext) extends BaseController with RendersItemResponse with Logging with ImplicitControllerExecutionContext {

  val capiLookup: CAPILookup = new CAPILookup(contentApiClient)
  val remoteRender: RemoteRender = new RemoteRender()

  private def isSupported(c: ApiContent) = c.isArticle || c.isLiveBlog || c.isSudoku
  override def canRender(i: ItemResponse): Boolean = i.content.exists(isSupported)
  override def renderItem(path: String)(implicit request: RequestHeader): Future[Result] = mapModel(path, Canonical)(render(path, _))

  def renderJson(path: String): Action[AnyContent] = {
    Action.async { implicit request =>
      mapModel(path, ArticleBlocks) {
        render(path, _)
      }
    }
  }

  def renderArticle(path: String): Action[AnyContent] = {
    Action.async { implicit request =>
      mapModel(path, ArticleBlocks)( article => {
        RenderingTierPicker.getTier(article) match {
          case RemoteRender => remoteRender.getArticle(ws, path, article)
          case RemoteRenderAMP => remoteRender.getAMPArticle(ws, path, article)
          case LocalRender => render(path, article)
          case _ => render(path, article)
        }
      })
    }
  }

  def renderEmail(path: String): Action[AnyContent] = {
    Action.async { implicit request =>
      mapModel(path, ArticleBlocks) {
        render(path, _)
      }
    }
  }

  def renderHeadline(path: String): Action[AnyContent] = Action.async { implicit request =>
    def responseFromHeadline(headline: Option[String]) = {
      headline
        .map(title => Cached(CacheTime.Default)(RevalidatableResult.Ok(title)))
        .getOrElse {
          log.warn(s"headline not found for $path")
          Cached(10)(WithoutRevalidationResult(NotFound))
        }
    }

    capiLookup
      .lookup(path, Some(ArticleBlocks))
      .map(_.content.map(_.webTitle))
      .map(responseFromHeadline)
  }

  private def getJson(article: ArticlePage)(implicit request: RequestHeader) = {
    val contentFieldsJson = if (request.isGuuiJson) List(
      "contentFields" -> Json.toJson(ContentFields(article.article)),
      "tags" -> Json.toJson(article.article.tags)) else List()
    List(("html", views.html.fragments.articleBody(article))) ++ contentFieldsJson
  }

  private def getGuuiJson(article: ArticlePage)(implicit request: RequestHeader): String =
    DotcomponentsDataModel.toJsonString(DotcomponentsDataModel.fromArticle(article, request))

  private def render(path: String, article: ArticlePage)(implicit request: RequestHeader): Future[Result] = {
    Future {
      request.getRequestFormat match {
        case JsonFormat if request.isGuui => common.renderJson(getGuuiJson(article), article)
        case JsonFormat => common.renderJson(getJson(article), article)
        case EmailFormat => common.renderEmail(ArticleEmailHtmlPage.html(article), article)
        case HtmlFormat => common.renderHtml(ArticleHtmlPage.html(article), article)
        case AmpFormat => common.renderHtml(views.html.articleAMP(article), article)
      }
    }
  }

  private def mapModel(path: String, range: BlockRange)(render: ArticlePage => Future[Result])(implicit request: RequestHeader): Future[Result] = {
    capiLookup
      .lookup(path, Some(range))
      .map(responseToModelOrResult)
      .recover(convertApiExceptions)
      .flatMap {
        case Left(model) => render(model)
        case Right(other) => Future.successful(RenderOtherStatus(other))
      }
  }

  private def responseToModelOrResult(response: ItemResponse)(implicit request: RequestHeader): Either[ArticlePage, Result] = {
    val supportedContent: Option[ContentType] = response.content.filter(isSupported).map(Content(_))

    ModelOrResult(supportedContent, response) match {
      case Left(article:Article) => Left(ArticlePage(article, StoryPackages(article, response)))
      case Right(r) => Right(r)
      case _ => Right(NotFound)
    }

  }

}
