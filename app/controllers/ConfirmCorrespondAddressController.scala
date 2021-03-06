/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import auth.AuthorisedActions
import com.google.inject.{Inject, Singleton}
import common.{Constants, KeystoreKeys}
import config.AppConfig
import connectors.KeystoreConnector
import forms.ConfirmCorrespondAddressForm
import models.{AddressModel, CompanyRegistrationReviewDetailsModel, ConfirmCorrespondAddressModel, ProvideCorrespondAddressModel}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent}
import services.RegisteredBusinessCustomerService
import uk.gov.hmrc.http.cache.client.CacheMap
import uk.gov.hmrc.play.frontend.controller.FrontendController
import views.html.registrationInformation.ConfirmCorrespondAddress
import play.api.i18n.{I18nSupport, MessagesApi}
import uk.gov.hmrc.play.health.routes
import utils.CountriesHelper

import scala.concurrent.Future
import uk.gov.hmrc.http.HeaderCarrier

@Singleton
class ConfirmCorrespondAddressController @Inject()(authorised: AuthorisedActions,
                                                   keystoreConnector: KeystoreConnector,
                                                   registeredBusinessCustomerService: RegisteredBusinessCustomerService,
                                                   confirmCorrespondAddressForm: ConfirmCorrespondAddressForm,
                                                   implicit val countriesHelper: CountriesHelper,
                                                   val messagesApi: MessagesApi,
                                                   implicit val applicationConfig: AppConfig)
  extends FrontendController with I18nSupport {

  def redirect(tokenId: Option[String]): Action[AnyContent] = { Action.async { implicit request =>
    for {
      tok <- if (tokenId.isEmpty) Future{CacheMap("", Map("" -> Json.toJson("")))}
            else keystoreConnector.saveFormData[String](KeystoreKeys.tokenId, tokenId.get)

    } yield Redirect(routes.ConfirmCorrespondAddressController.show().url)
    }
  }

  private def getConfirmCorrespondenceModels(implicit headerCarrier: HeaderCarrier) : Future[(Option[ConfirmCorrespondAddressModel],
    CompanyRegistrationReviewDetailsModel)] = {
    for {
      confirmCorrespondAddress <- keystoreConnector.fetchAndGetFormData[ConfirmCorrespondAddressModel](KeystoreKeys.confirmContactAddress)
      companyDetails <- registeredBusinessCustomerService.getReviewBusinessCustomerDetails
    } yield (confirmCorrespondAddress, companyDetails.get)
  }


  def show: Action[AnyContent] =

    authorised.async { implicit user => implicit request =>
    getConfirmCorrespondenceModels.map {
      case (Some(confirmCorrespondAddress),companyDetails) =>
        Ok(ConfirmCorrespondAddress(confirmCorrespondAddressForm.form.fill(confirmCorrespondAddress),companyDetails))
      case (None,companyDetails) => Ok(ConfirmCorrespondAddress(confirmCorrespondAddressForm.form,companyDetails))
    }
  }

  def submit: Action[AnyContent] = authorised.async { implicit user => implicit request =>
    confirmCorrespondAddressForm.form.bindFromRequest().fold(
      formWithErrors => {
        getConfirmCorrespondenceModels.map {
          case (_,companyDetails) => BadRequest(ConfirmCorrespondAddress(formWithErrors,companyDetails))
        }
      },
      validFormData => {
        keystoreConnector.saveFormData(KeystoreKeys.confirmContactAddress, validFormData)

        validFormData.contactAddressUse match {
          case Constants.StandardRadioButtonYesValue => {
            registeredBusinessCustomerService.getReviewBusinessCustomerDetails.map(companyDetails => {
              keystoreConnector.saveFormData(KeystoreKeys.provideCorrespondAddress,
                Json.toJson(companyDetails.get.businessAddress).as[ProvideCorrespondAddressModel])
            })
            keystoreConnector.saveFormData(KeystoreKeys.backLinkConfirmCorrespondAddress,
              routes.ConfirmCorrespondAddressController.show().url)
            Future.successful(Redirect(routes.ContactDetailsSubscriptionController.show()))
          }
          case Constants.StandardRadioButtonNoValue => Future.successful(Redirect(routes.ProvideCorrespondAddressController.show()))
        }
      }
    )
  }
}
