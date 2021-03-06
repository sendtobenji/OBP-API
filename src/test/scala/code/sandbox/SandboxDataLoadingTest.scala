/**
Open Bank Project - API
Copyright (C) 2011, 2013, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

  */
package code.sandbox

import java.util.Date

import bootstrap.liftweb.ToSchemify
import code.TestServer
import code.api.test.{SendServerRequests, APIResponse}
import code.api.v1_2_1.APIMethods121
import code.model.dataAccess._
import code.model.{TransactionId, AccountId, BankId}
import code.users.Users
import code.views.Views
import dispatch._
import net.liftweb.json.JsonAST.JObject
import net.liftweb.mapper.By
import net.liftweb.util.Props
import org.scalatest.{BeforeAndAfterEach, ShouldMatchers, FlatSpec}
import net.liftweb.json.JsonDSL._
import net.liftweb.json._
import net.liftweb.json.Serialization.write
import code.bankconnectors.Connector
import net.liftweb.common.{Full, Empty}
import net.liftweb.mongodb._

class SandboxDataLoadingTest extends FlatSpec with SendServerRequests with ShouldMatchers with BeforeAndAfterEach {

  val SUCCESS: Int = 201
  val FAILED: Int = 400

  implicit val formats = Serialization.formats(NoTypeHints)

  val server = TestServer
  def baseRequest = host(server.host, server.port)

  def sandboxApiPrefix = baseRequest / "obp" / "vsandbox"

  //users should automatically be assigned the "hostname" as a provider (for now at least)
  val defaultProvider = Props.get("hostname").openOrThrowException("no hostname set")

  val theImportToken = Props.get("sandbox_data_import_secret").openOrThrowException("sandbox_data_import_secret not set")


  override def beforeEach() = {
    //drop database tables after the tests
    MongoDB.getDb(DefaultMongoIdentifier).foreach(_.dropDatabase())
    ToSchemify.models.foreach(_.bulkDelete_!!())
  }


  def toJsonArray(xs : List[String]) : String = {
    xs.mkString("[", ",", "]")
  }

  def createImportJson(banks: List[JValue], users: List[JValue], accounts : List[JValue], transactions : List[JValue]) : String = {
    val json =
      ("banks" -> banks) ~
      ("users" -> users) ~
      ("accounts" -> accounts) ~
      ("transactions" -> transactions)
    compact(render(json))
  }

  // posts the json with the correct secret token
  def postImportJson(json : String) : APIResponse = {
    postImportJson(json, Some(theImportToken))
  }

  def postImportJson(json : String, secretToken : Option[String]) : APIResponse = {
    val base = sandboxApiPrefix / "v1.0" / "data-import"
    val request = secretToken match {
      case Some(t) => base <<? Map("secret_token" -> t)
      case None => base
    }
    makePostRequest(request, json)
  }

  def verifyBankCreated(bank : SandboxBankImport) = {
    val bankId = BankId(bank.id)
    val foundBankBox = Connector.connector.vend.getBank(bankId)

    foundBankBox.isDefined should equal(true)

    val foundBank = foundBankBox.get

    foundBank.bankId should equal(bankId)
    foundBank.shortName should equal(bank.short_name)
    foundBank.fullName should equal(bank.full_name)
    foundBank.logoUrl should equal(bank.logo)
    foundBank.websiteUrl should equal(bank.website)
  }

  def verifyUserCreated(user : SandboxUserImport) = {
    val foundUserBox = Users.users.vend.getUserByProviderId(defaultProvider, user.email)
    foundUserBox.isDefined should equal(true)

    val foundUser = foundUserBox.get

    foundUser.provider should equal(defaultProvider)
    foundUser.idGivenByProvider should equal(user.email)
    foundUser.emailAddress should equal(user.email)
    foundUser.name should equal(user.display_name)
  }

  def verifyAccountCreated(account : SandboxAccountImport) = {
    val accId = AccountId(account.id)
    val bankId = BankId(account.bank)
    val foundAccountBox = Connector.connector.vend.getBankAccount(bankId, accId)
    foundAccountBox.isDefined should equal(true)

    val foundAccount = foundAccountBox.get

    foundAccount.bankId should equal(bankId)
    foundAccount.accountId should equal(accId)
    foundAccount.label should equal(account.label)
    foundAccount.number should equal(account.number)
    foundAccount.accountType should equal(account.`type`)
    foundAccount.iban should equal(Some(account.IBAN))
    foundAccount.balance.toString should equal(account.balance.amount)
    foundAccount.currency should equal(account.balance.currency)

    foundAccount.owners.map(_.emailAddress) should equal(account.owners.toSet)

    if(account.generate_public_view) {
      foundAccount.publicViews.size should equal(1)
    } else {
      foundAccount.publicViews.size should equal(0)
    }

    val owner = Users.users.vend.getUserByProviderId(defaultProvider, foundAccount.owners.toList.head.emailAddress).get

    //there should be an owner view
    val views = foundAccount.views(owner).get
    val ownerView = views.find(v => v.viewId.value == "owner")
    ownerView.isDefined should equal(true)

    //and the owners should have access to it
    ownerView.get.users.map(_.idGivenByProvider).toSet should equal(account.owners.toSet)
  }

  def verifyTransactionCreated(transaction : SandboxTransactionImport, accountsUsed : List[SandboxAccountImport]) = {
    val bankId = BankId(transaction.this_account.bank)
    val accountId = AccountId(transaction.this_account.id)
    val transactionId = TransactionId(transaction.id)
    val foundTransactionBox = Connector.connector.vend.getTransaction(bankId, accountId, transactionId)

    foundTransactionBox.isDefined should equal(true)

    val foundTransaction = foundTransactionBox.get

    foundTransaction.id should equal(transactionId)
    foundTransaction.bankId should equal(bankId)
    foundTransaction.accountId should equal(accountId)
    foundTransaction.description should equal(Some(transaction.details.description))
    foundTransaction.balance.toString should equal(transaction.details.new_balance)
    foundTransaction.amount.toString should equal(transaction.details.value)

    def toDate(dateString : String) : Date = {
      APIMethods121.DateParser.parse(dateString).get
    }

    foundTransaction.startDate.getTime should equal(toDate(transaction.details.posted).getTime)
    foundTransaction.finishDate.getTime should equal(toDate(transaction.details.completed).getTime)

    //a counterparty should exist
    val otherAcc = foundTransaction.otherAccount
    otherAcc.id should not be empty
    otherAcc.originalPartyAccountId should equal(accountId)
    otherAcc.originalPartyBankId should equal(bankId)
    val otherAccMeta = otherAcc.metadata
    otherAccMeta.getPublicAlias should not be empty

    //if a counterparty was originally specified in the import data, it should correspond to that
    //counterparty
    if(transaction.counterparty.isDefined) {
      transaction.counterparty.get.name match {
        case Some(name) => otherAcc.label should equal(name)
        case None => otherAcc.label.nonEmpty should equal(true) //it should generate a counterparty label
      }

      transaction.counterparty.get.account_number match {
        case Some(number) => otherAcc.number should equal(number)
        case None => otherAcc.number should equal("")
      }
    }

  }

  def addField(json : JValue, fieldName : String, fieldValue : String) = {
    json.transform{
      case JObject(fields) => JObject(JField(fieldName, fieldValue) :: fields)
    }
  }

  def removeField(json : JValue, fieldName : String) = {
    json.remove {
      case JField(`fieldName`, _) => true
      case _ => false
    }
  }

  def removeField(json : JValue, fieldSpecifier : List[String]) = {
    json.replace(fieldSpecifier, JNothing)
  }

  implicit class JValueWithSingleReplace(jValue : JValue) {
    def replace(fieldName : String, fieldValue : String) =
      jValue.replace(List(fieldName), fieldValue)
  }

  //TODO: remove this method?
  def replaceField(json : JValue, fieldName : String, fieldValue : String) =
    json.replace(List(fieldName), fieldValue)

  //TODO: remove this method?
  def replaceDisplayName(json : JValue, displayName : String) =
    replaceField(json, "display_name", displayName)

  def addIdField(json : JValue, id : String) =
    addField(json, "id", id)

  def removeIdField(json : JValue) =
    removeField(json, "id")

  def addEmailField(json : JValue, email : String) =
    addField(json, "email", email)

  def removeEmailField(json : JValue) =
    removeField(json, "email")

  val bank1 = SandboxBankImport(id = "bank1", short_name = "bank 1", full_name = "Bank 1 Inc.",
    logo = "http://example.com/logo", website = "http://example.com")
  val bank2 = SandboxBankImport(id = "bank2", short_name = "bank 2", full_name = "Bank 2 Inc.",
    logo = "http://example.com/logo2", website = "http://example.com/2")

  val standardBanks = bank1 :: bank2 :: Nil

  val user1 = SandboxUserImport(email = "user1@example.com", password = "qwerty", display_name = "User 1")
  val user2 = SandboxUserImport(email = "user2@example.com", password = "qwerty", display_name = "User 2")

  val standardUsers = user1 :: user2 :: Nil

  val account1AtBank1 = SandboxAccountImport(id = "account1", bank = "bank1", label = "Account 1 at Bank 1",
    number = "1", `type` = "savings", IBAN = "1234567890", generate_public_view = true, owners = List(user1.email),
    balance = SandboxBalanceImport(currency = "EUR", amount = "1000.00"))

  val account2AtBank1 = SandboxAccountImport(id = "account2", bank = "bank1", label = "Account 2 at Bank 1",
    number = "2", `type` = "current", IBAN = "91234567890", generate_public_view = false, owners = List(user2.email),
    balance = SandboxBalanceImport(currency = "EUR", amount = "2000.00"))

  val account1AtBank2 = SandboxAccountImport(id = "account1", bank = "bank2", label = "Account 1 at Bank 2",
    number = "22", `type` = "savings", IBAN = "21234567890", generate_public_view = false, owners = List(user1.email, user2.email),
    balance = SandboxBalanceImport(currency = "EUR", amount = "1500.00"))

  val standardAccounts = account1AtBank1 :: account2AtBank1 :: account1AtBank2 :: Nil

  val counterparty1 = SandboxTransactionCounterparty(name = Some("Acme Inc."), account_number = Some("12345-B"))

  val transactionWithCounterparty = SandboxTransactionImport(id = "transaction-with-counterparty",
    this_account = SandboxAccountIdImport(id = account1AtBank1.id, bank=account1AtBank1.bank),
    counterparty = Some(counterparty1),
    details = SandboxAccountDetailsImport(
      `type` = "SEPA",
      description = "some description",
      posted = "2012-03-07T00:00:00.001Z",
      completed = "2012-04-07T00:00:00.001Z",
      new_balance = "1244.00",
      value = "-135.33"
    ))

  val transactionWithoutCounterparty = SandboxTransactionImport(id = "transaction-without-counterparty",
    this_account = SandboxAccountIdImport(id = account1AtBank1.id, bank=account1AtBank1.bank),
    counterparty = None,
    details = SandboxAccountDetailsImport(
      `type` = "SEPA",
      description = "this is a description",
      posted = "2012-03-07T00:00:00.001Z",
      completed = "2012-04-07T00:00:00.001Z",
      new_balance = "1244.00",
      value = "-135.33"
    ))

  val standardTransactions = transactionWithCounterparty :: transactionWithoutCounterparty :: Nil

  /**
   *
   *
   * Tests below
   *
   *
   */

  "Data import" should "work in the general case" in {

    //same transaction id as another one used, but for a different bank account, so it should work
    val anotherTransaction = SandboxTransactionImport(id = transactionWithoutCounterparty.id,
      this_account = SandboxAccountIdImport(id = account1AtBank2.id, bank=account1AtBank2.bank),
      counterparty = None,
      details = SandboxAccountDetailsImport(
        `type` = "SEPA",
        description = "this is another description",
        posted = "2012-03-07T00:00:00.001Z",
        completed = "2012-04-07T00:00:00.001Z",
        new_balance = "1224.00",
        value = "-135.38"
      ))

    val blankCounterpartyNameTransaction  = SandboxTransactionImport(id = "blankCounterpartNameTransaction",
      this_account = SandboxAccountIdImport(id = account1AtBank2.id, bank=account1AtBank2.bank),
      counterparty = Some(SandboxTransactionCounterparty(None, Some("123456-AVB"))),
      details = SandboxAccountDetailsImport(
        `type` = "SEPA",
        description = "this is another description",
        posted = "2012-03-07T00:00:00.001Z",
        completed = "2012-04-07T00:00:00.001Z",
        new_balance = "1224.00",
        value = "-135.38"
      ))

    val blankCounterpartyAccountNumberTransaction  = SandboxTransactionImport(id = "blankCounterpartAccountNumberTransaction",
      this_account = SandboxAccountIdImport(id = account1AtBank2.id, bank=account1AtBank2.bank),
      counterparty = Some(SandboxTransactionCounterparty(Some("Piano Repair"), None)),
      details = SandboxAccountDetailsImport(
        `type` = "SEPA",
        description = "this is another description",
        posted = "2012-03-07T00:00:00.001Z",
        completed = "2012-04-07T00:00:00.001Z",
        new_balance = "1224.00",
        value = "-135.38"
      ))

    val banks = standardBanks
    val users = standardUsers
    val accounts = standardAccounts
    val transactions = anotherTransaction :: blankCounterpartyNameTransaction :: blankCounterpartyAccountNumberTransaction :: standardTransactions

    val importJson = SandboxDataImport(banks, users, accounts, transactions)
    val response = postImportJson(write(importJson))

    response.code should equal(SUCCESS)

    banks.foreach(verifyBankCreated)
    users.foreach(verifyUserCreated)
    accounts.foreach(verifyAccountCreated)
    transactions.foreach(verifyTransactionCreated(_, accounts))
  }

  it should "not allow data to be imported without a secret token" in {
    val importJson = SandboxDataImport(standardBanks, standardUsers, standardAccounts, standardTransactions)
    val response = postImportJson(write(importJson), None)

    response.code should equal(403)

    //nothing should be created
    Connector.connector.vend.getBanks should equal(Nil)
  }

  it should "not allow data to be imported with an invalid secret token" in {
    val importJson = SandboxDataImport(standardBanks, standardUsers, standardAccounts, standardTransactions)
    val badToken = "12345"
    badToken should not equal(theImportToken)
    val response = postImportJson(write(importJson), Some(badToken))

    response.code should equal(403)

    //nothing should be created
    Connector.connector.vend.getBanks should equal(Nil)
  }

  it should "require banks to have non-empty ids" in {

    //no banks should exist initially
    Connector.connector.vend.getBanks.size should equal(0)

    val bank1Json = Extraction.decompose(bank1)

    val bankWithoutId = removeIdField(bank1Json)

    def getResponse(bankJson : JValue) = {
      val json = createImportJson(List(bankJson), Nil, Nil, Nil)
      postImportJson(json)
    }

    getResponse(bankWithoutId).code should equal(FAILED)

    //no banks should have been created
    Connector.connector.vend.getBanks.size should equal(0)

    val bankWithEmptyId = addIdField(bankWithoutId, "")
    getResponse(bankWithEmptyId).code should equal(FAILED)

    //no banks should have been created
    Connector.connector.vend.getBanks.size should equal(0)

    //Check that the same json becomes valid when a non-empty id is added
    val validId = "foo"
    val bankWithValidId = addIdField(bankWithoutId, validId)
    val response = getResponse(bankWithValidId)
    response.code should equal(SUCCESS)

    //Check the bank was created
    val banks = Connector.connector.vend.getBanks
    banks.size should equal(1)
    val createdBank  = banks(0)

    createdBank.bankId should equal(BankId(validId))
    createdBank.shortName should equal(bank1.short_name)
    createdBank.fullName should equal(bank1.full_name)
    createdBank.logoUrl should equal(bank1.logo)
    createdBank.websiteUrl should equal(bank1.website)
  }

  it should "not allow multiple banks with the same id" in {
    //no banks should exist initially
    Connector.connector.vend.getBanks.size should equal(0)

    val bank1AsJValue = Extraction.decompose(bank1)

    val baseOtherBank =
      ("short_name" -> {bank1.short_name + "2"}) ~
      ("full_name" -> {bank1.full_name + "2"}) ~
      ("logo" -> {bank1.logo + "2"}) ~
      ("website" -> {bank1.website + "2"})

    //same id as bank1, but different other attributes
    val bankWithSameId = addIdField(baseOtherBank, bank1.id)

    def getResponse(bankJsons : List[JValue]) = {
      val json = createImportJson(bankJsons, Nil, Nil, Nil)
      postImportJson(json)
    }

    getResponse(List(bank1AsJValue, bankWithSameId)).code should equal(FAILED)

    //now try again but this time with a different id
    val validOtherBank = addIdField(baseOtherBank, {bank1.id + "2"})

    getResponse(List(bank1AsJValue, validOtherBank)).code should equal(SUCCESS)

    //check that two banks were created
    val banks = Connector.connector.vend.getBanks
    banks.size should equal(2)
  }

  it should "fail if a specified bank already exists" in {
    def getResponse(bankJsons : List[JValue]) = {
      val json = createImportJson(bankJsons, Nil, Nil, Nil)
      postImportJson(json)
    }

    val bank1Json = Extraction.decompose(bank1)

    //add bank1
    getResponse(List(bank1Json)).code should equal(SUCCESS)


    val otherBank = bank2
    //when we try to add bank1 and another valid bank it should now fail
    getResponse(List(bank1Json, Extraction.decompose(bank2))).code should equal(FAILED)

    //and the other bank should not have been created
    Connector.connector.vend.getBank(BankId(otherBank.id)).isDefined should equal(false)
  }

  it should "require users to have valid emails" in {

    def getResponse(userJson : JValue) = {
      val json = createImportJson(Nil, List(userJson), Nil, Nil)
      postImportJson(json)
    }

    val user1AsJson = Extraction.decompose(user1)

    val userWithoutEmail = removeEmailField(user1AsJson)

    getResponse(userWithoutEmail).code should equal(FAILED)

    val userWithEmptyEmail = addEmailField(userWithoutEmail, "")

    //there should be no user with a blank id before we try to add one
    Users.users.vend.getUserByProviderId(defaultProvider, "") should equal(Empty)

    getResponse(userWithEmptyEmail).code should equal(FAILED)

    //there should still be no user with a blank email
    Users.users.vend.getUserByProviderId(defaultProvider, "") should equal(Empty)

    //invalid email should fail
    val invalidEmail = "foooo"
    val userWithInvalidEmail = addEmailField(userWithoutEmail, invalidEmail)

    getResponse(userWithInvalidEmail).code should equal(FAILED)

    //there should still be no user
    Users.users.vend.getUserByProviderId(defaultProvider, invalidEmail) should equal(Empty)

    val validEmail = "test@example.com"
    val userWithValidEmail = addEmailField(userWithoutEmail, validEmail)

    getResponse(userWithValidEmail).code should equal(SUCCESS)

    //a user should now have been created
    val createdUser = Users.users.vend.getUserByProviderId(defaultProvider, validEmail)
    createdUser.isDefined should equal(true)
    createdUser.get.provider should equal(defaultProvider)
    createdUser.get.idGivenByProvider should equal(validEmail)
    createdUser.get.emailAddress should equal(validEmail)

  }

  it should "not allow multiple users with the same email" in {

    def getResponse(userJsons : List[JValue]) = {
      val json = createImportJson(Nil, userJsons, Nil, Nil)
      postImportJson(json)
    }

    //emails of the user we will eventually create to show multiple users with different ids are possible
    val secondUserEmail = "user-two@example.com"

    val user1Json = Extraction.decompose(user1)

    val differentDisplayName = "Jessica Bloggs"
    differentDisplayName should not equal(user1.display_name)
    val userWithSameEmailAsUser1 = user1Json.replace("display_name", differentDisplayName)

    //neither of the users should exist initially
    Users.users.vend.getUserByProviderId(defaultProvider, user1.email) should equal(Empty)
    Users.users.vend.getUserByProviderId(defaultProvider, secondUserEmail) should equal(Empty)

    getResponse(List(user1Json, userWithSameEmailAsUser1)).code should equal(FAILED)

    //no user with firstUserId should be created
    Users.users.vend.getUserByProviderId(defaultProvider, user1.email) should equal(Empty)

    //when we only alter the id (display name stays the same), it should work
    val userWithEmail2 = userWithSameEmailAsUser1.replace("email", secondUserEmail)

    getResponse(List(user1Json, userWithEmail2)).code should equal(SUCCESS)

    //and both users should be created
    val firstUser = Users.users.vend.getUserByProviderId(defaultProvider, user1.email)
    val secondUser = Users.users.vend.getUserByProviderId(defaultProvider, secondUserEmail)

    firstUser.isDefined should equal(true)
    secondUser.isDefined should equal(true)

    firstUser.get.idGivenByProvider should equal(user1.email)
    secondUser.get.idGivenByProvider should equal(secondUserEmail)

    firstUser.get.emailAddress should equal(user1.email)
    secondUser.get.emailAddress should equal(secondUserEmail)

    firstUser.get.name should equal(user1.display_name)
    secondUser.get.name should equal(differentDisplayName)
  }

  it should "fail if a specified user already exists" in {
    def getResponse(userJsons : List[JValue]) = {
      val json = createImportJson(Nil, userJsons, Nil, Nil)
      postImportJson(json)
    }

    val user1Json = Extraction.decompose(user1)

    //add user1
    getResponse(List(user1Json)).code should equal(SUCCESS)


    val otherUser = user2
    //when we try to add user1 and another valid new user it should now fail
    getResponse(List(user1Json, Extraction.decompose(otherUser))).code should equal(FAILED)

    //and the other user should not have been created
    Users.users.vend.getUserByProviderId(defaultProvider, otherUser.email)
  }

  it should "fail if a user's password is missing or empty" in {
    def getResponse(userJsons : List[JValue]) = {
      val json = createImportJson(Nil, userJsons, Nil, Nil)
      postImportJson(json)
    }

    val goodUser = Extraction.decompose(user1)

    val userWithoutPassword = removeField(goodUser, "password")
    getResponse(List(userWithoutPassword)).code should equal(FAILED)
    //no user should be created
    Users.users.vend.getUserByProviderId(defaultProvider, user1.email).isDefined should equal(false)

    val userWithBlankPassword = replaceField(goodUser, "password", "")
    getResponse(List(userWithBlankPassword)).code should equal(FAILED)
    //no user should be created
    Users.users.vend.getUserByProviderId(defaultProvider, user1.email).isDefined should equal(false)

    //check that a normal password is okay
    getResponse(List(goodUser)).code should equal(SUCCESS)
    Users.users.vend.getUserByProviderId(defaultProvider, user1.email).isDefined should equal(true)
  }

  it should "set user passwords properly" in {
    def getResponse(userJsons : List[JValue]) = {
      val json = createImportJson(Nil, userJsons, Nil, Nil)
      postImportJson(json)
    }

    getResponse(List(Extraction.decompose(user1))).code should equal(SUCCESS)

    //TODO: we shouldn't reference OBPUser here as it is an implementation, but for now there
    //is no way to check User (the trait) passwords
    val createdOBPUserBox = OBPUser.find(By(OBPUser.email, user1.email))
    createdOBPUserBox.isDefined should equal(true)

    val createdOBPUser = createdOBPUserBox.get
    createdOBPUser.password.match_?(user1.password) should equal(true)
  }

  it should "require accounts to have non-empty ids" in {

    def getResponse(accountJsons : List[JValue]) = {
      val banks = standardBanks.map(Extraction.decompose)
      val users = standardUsers.map(Extraction.decompose)
      val json = createImportJson(banks, users, accountJsons, Nil)
      postImportJson(json)
    }

    val acc1AtBank1Json = Extraction.decompose(account1AtBank1)
    val accountWithoutId = removeIdField(acc1AtBank1Json)

    getResponse(List(accountWithoutId)).code should equal(FAILED)

    val accountWithEmptyId = addIdField(accountWithoutId, "")

    getResponse(List(accountWithEmptyId)).code should equal(FAILED)

    //no account should exist with an empty id
    Connector.connector.vend.getBankAccount(BankId(account1AtBank1.bank), AccountId("")).isDefined should equal(false)

    getResponse(List(acc1AtBank1Json)).code should equal(SUCCESS)

    //an account should now exist
    verifyAccountCreated(account1AtBank1)
  }

  it should "not allow multiple accounts at the same bank with the same id" in {

    def getResponse(accountJsons : List[JValue]) = {
      val banks = standardBanks.map(Extraction.decompose)
      val users = standardUsers.map(Extraction.decompose)
      val json = createImportJson(banks, users, accountJsons, Nil)
      postImportJson(json)
    }

    val account1AtBank1Json = Extraction.decompose(account1AtBank1)
    val accountWithSameId = replaceField(Extraction.decompose(account2AtBank1), "id", account1AtBank1.id)
    //might be nice to test a case where the only similar attribute between the accounts is the id
    getResponse(List(account1AtBank1Json, accountWithSameId)).code should equal(FAILED)

    //no accounts should have been created
    Connector.connector.vend.getBankAccount(BankId(account1AtBank1.bank), AccountId(account1AtBank1.id)).isDefined should equal(false)

    val accountIdTwo = "2"
    accountIdTwo should not equal(account1AtBank1.id)

    val accountWithDifferentId = replaceField(accountWithSameId, "id", accountIdTwo)

    getResponse(List(account1AtBank1Json, accountWithDifferentId)).code should equal(SUCCESS)

    //two accounts should have been created
    Connector.connector.vend.getBankAccount(BankId(account1AtBank1.bank), AccountId(account1AtBank1.id)).isDefined should equal(true)
    Connector.connector.vend.getBankAccount(BankId(account1AtBank1.bank), AccountId(accountIdTwo)).isDefined should equal(true)

  }

  it should "fail if a specified account already exists" in {
    def getResponse(accountJsons : List[JValue]) = {
      val banks = standardBanks.map(Extraction.decompose)
      val users = standardUsers.map(Extraction.decompose)
      val json = createImportJson(banks, users, accountJsons, Nil)
      postImportJson(json)
    }
    val account1AtBank1Json = Extraction.decompose(account1AtBank1)

    //add account1AtBank1
    getResponse(List(account1AtBank1Json)).code should equal(SUCCESS)

    val otherAccount = account1AtBank2
    //when we try to add account1AtBank1 and another valid account it should now fail
    getResponse(List(account1AtBank1Json, Extraction.decompose(otherAccount))).code should equal(FAILED)

    //and the other account should not have been created
    Connector.connector.vend.getBankAccount(BankId(otherAccount.bank), AccountId(otherAccount.id)).isDefined should equal(false)
  }

  it should "not allow an account to have a bankId not specified in the imported banks" in {

    val badBankId = "asdf"

    def getResponse(accountJsons : List[JValue]) = {
      standardBanks.exists(b => b.id == badBankId) should equal(false)
      val banks = standardBanks.map(Extraction.decompose)

      val users = standardUsers.map(Extraction.decompose)
      val json = createImportJson(banks, users, accountJsons, Nil)
      postImportJson(json)
    }

    val badBankAccount = replaceField(Extraction.decompose(account1AtBank1), "bank", badBankId)

    getResponse(List(badBankAccount)).code should equal(FAILED)

    //no account should have been created
    Connector.connector.vend.getBankAccount(BankId(badBankId), AccountId(account1AtBank1.id)).isDefined should equal(false)
  }

  it should "not allow an account to be created without an owner" in {
    def getResponse(accountJsons : List[JValue]) = {
      val banks = standardBanks.map(Extraction.decompose)
      val users = standardUsers.map(Extraction.decompose)

      val json = createImportJson(banks, users, accountJsons, Nil)
      postImportJson(json)
    }

    val acc1AtBank1Json = Extraction.decompose(account1AtBank1)

    val accountWithNoOwnerField = removeField(acc1AtBank1Json, "owners")

    getResponse(List(accountWithNoOwnerField)).code should equal(FAILED)

    val accountWithNilOwners = Extraction.decompose(account1AtBank1.copy(owners = Nil))

    getResponse(List(accountWithNilOwners)).code should equal(FAILED)
  }

  it should "not allow an account to be created with an owner not specified in data import users" in {

    val users = standardUsers
    val banks = standardBanks

    def getResponse(accountJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose), users.map(Extraction.decompose), accountJsons, Nil)
      postImportJson(json)
    }

    val nonExistentOwnerEmail = "asdfasdfasdf@example.com"
    users.exists(u => u.email == nonExistentOwnerEmail) should equal(false)

    val accountWithInvalidOwner = account1AtBank1.copy(owners = List(nonExistentOwnerEmail))

    getResponse(List(Extraction.decompose(accountWithInvalidOwner))).code should equal(FAILED)

    //it should not have been created
    Connector.connector.vend.getBankAccount(BankId(accountWithInvalidOwner.bank), AccountId(accountWithInvalidOwner.id)).isDefined should equal(false)

    //a mix of valid an invalid owners should also not work
    val accountWithSomeValidSomeInvalidOwners = accountWithInvalidOwner.copy(owners = List(accountWithInvalidOwner.owners + user1.email))
    getResponse(List(Extraction.decompose(accountWithSomeValidSomeInvalidOwners))).code should equal(FAILED)

    //it should not have been created
    Connector.connector.vend.getBankAccount(BankId(accountWithSomeValidSomeInvalidOwners.bank), AccountId(accountWithSomeValidSomeInvalidOwners.id)).isDefined should equal(false)

  }

  it should "not allow multiple accounts at the same bank with the same account number" in {
    val users = standardUsers
    val banks = standardBanks

    def getResponse(accountJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose), users.map(Extraction.decompose), accountJsons, Nil)
      postImportJson(json)
    }

    val acc1 = account1AtBank1
    val acc2 = account2AtBank1

    val acc1Json = Extraction.decompose(acc1)
    val acc2Json = Extraction.decompose(acc2)
    val sameNumberJson = replaceField(acc2Json, "number", acc1.number)

    getResponse(List(acc1Json, sameNumberJson)).code should equal(FAILED)

    //no accounts should have been created
    Connector.connector.vend.getBankAccount(BankId(acc1.bank), AccountId(acc1.id)).isDefined should equal(false)
    Connector.connector.vend.getBankAccount(BankId(acc1.bank), AccountId(acc2.id)).isDefined should equal(false)

    //check it works with the normal different number
    getResponse(List(acc1Json, acc2Json)).code should equal(SUCCESS)

    //and the accounts should be created
    Connector.connector.vend.getBankAccount(BankId(acc1.bank), AccountId(acc1.id)).isDefined should equal(true)
    Connector.connector.vend.getBankAccount(BankId(acc1.bank), AccountId(acc2.id)).isDefined should equal(true)
  }

  it should "require transactions to have non-empty ids" in {

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(standardBanks.map(Extraction.decompose),
        standardUsers.map(Extraction.decompose), standardAccounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    def transactionExists() : Boolean = {
      Connector.connector.vend.getTransaction(BankId(transactionWithoutCounterparty.this_account.bank),
        AccountId(transactionWithoutCounterparty.this_account.id),
        TransactionId(transactionWithoutCounterparty.id)).isDefined
    }

    val transactionJson = Extraction.decompose(transactionWithoutCounterparty)

    val missingIdTransaction = removeIdField(transactionJson)
    getResponse(List(missingIdTransaction)).code should equal(FAILED)
    transactionExists() should equal(false)

    val emptyIdTransaction = replaceField(transactionJson, "id", "")
    getResponse(List(emptyIdTransaction)).code should equal(FAILED)
    transactionExists() should equal(false)

    //the original transaction should work too (just to make sure it's not failing because we have, e.g. a bank id that doesn't exist)
    getResponse(List(transactionJson)).code should equal(SUCCESS)

    //it should exist now
    transactionExists() should equal(true)
  }

  it should "require transactions for a single account do not have the same id" in {

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(standardBanks.map(Extraction.decompose),
        standardUsers.map(Extraction.decompose), standardAccounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t1 = transactionWithoutCounterparty
    val t2 = transactionWithCounterparty

    //make sure the two transactions are for the same account have different ids
    t1.this_account.bank should equal(t2.this_account.bank)
    t1.this_account.id should equal(t2.this_account.id)
    t1.id should not equal(t2.id)

    val transactionJson = Extraction.decompose(t1)
    val transaction2Json = Extraction.decompose(t2)

    //now edit the second transaction to give it the same id as the first one
    val sameIdAsOtherTransaction = replaceField(transaction2Json, "id", t1.id)

    getResponse(List(transactionJson, sameIdAsOtherTransaction)).code should equal(FAILED)

    //Neither should exist
    Connector.connector.vend.getTransaction(BankId(t1.this_account.bank),
      AccountId(t1.this_account.id),
      TransactionId(t1.id)).isDefined should equal(false)

    //now make sure it's not failing because we have, e.g. a bank id that doesn't exist by checking the originals worked
    getResponse(List(transactionJson, transaction2Json)).code should equal(SUCCESS)

    //both should exist now
    Connector.connector.vend.getTransaction(BankId(t1.this_account.bank),
      AccountId(t1.this_account.id),
      TransactionId(t1.id)).isDefined should equal(true)

    Connector.connector.vend.getTransaction(BankId(t2.this_account.bank),
      AccountId(t2.this_account.id),
      TransactionId(t2.id)).isDefined should equal(true)
  }

  it should "fail if a specified transaction already exists" in {
    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(standardBanks.map(Extraction.decompose),
        standardUsers.map(Extraction.decompose), standardAccounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t1Json = Extraction.decompose(transactionWithoutCounterparty)

    //add transaction
    getResponse(List(t1Json)).code should equal(SUCCESS)

    val otherTransaction = transactionWithCounterparty

    //when we try to add t1Json and another valid transaction it should now fail
    getResponse(List(t1Json, Extraction.decompose(otherTransaction))).code should equal(FAILED)

    //and no new transaction should exist
    Connector.connector.vend.getTransaction(BankId(otherTransaction.this_account.bank),
      AccountId(otherTransaction.this_account.id),
      TransactionId(otherTransaction.id)).isDefined should equal(false)
  }

  it should "not create any transactions when one has an invalid this_account" in {
    val banks = standardBanks
    val users = standardUsers
    val accounts = standardAccounts

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty

    val validTransaction = Extraction.decompose(t)

    //ensure bank is correct
    banks.exists(b => b.id == t.this_account.bank) should equal(true)

    val invalidAccountId = "asdfasdfasdf"
    //ensure account id is invalid
    accounts.exists(a => a.bank == t.this_account.bank && a.id == invalidAccountId) should equal(false)

    //check one where the bank id exists, but the account id doesn't
    val invalidAccTransaction = validTransaction.replace(List("this_account","id"), invalidAccountId)

    getResponse(List(invalidAccTransaction)).code should equal(FAILED)

    //transaction should not exist
    Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
      AccountId(invalidAccountId),
      TransactionId(t.id)).isDefined should equal(false)

    //now check one where the bankId is invalid

    val invalidBankId = "omommomom"
    //ensure bank is invalid
    banks.exists(b => b.id == invalidBankId) should equal(false)

    val invalidBankTransaction = validTransaction.replace(List("this_account", "bank"), invalidBankId)

    getResponse(List(invalidBankTransaction)).code should equal(FAILED)

    //transaction should not exist
    Connector.connector.vend.getTransaction(BankId(invalidBankId),
      AccountId(t.this_account.id),
      TransactionId(t.id)).isDefined should equal(false)

    //now make sure it works when all is well
    getResponse(List(validTransaction)).code should equal(SUCCESS)

    //transaction should exist
    Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
      AccountId(t.this_account.id),
      TransactionId(t.id)).isDefined should equal(true)

  }

  it should "allow counterparty name to be empty" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty
    val baseT = Extraction.decompose(t)
    val emptyCounterpartyNameTransaction = baseT.replace(List("counterparty", "name"), "")

    getResponse(List(emptyCounterpartyNameTransaction)).code should equal(SUCCESS)

    //check it was created, name is generated, and account number matches
    val createdTransaction = Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
      AccountId(t.this_account.id),
      TransactionId(t.id))

    createdTransaction.isDefined should equal(true)
    val created = createdTransaction.get

    created.otherAccount.label.nonEmpty should equal(true)
    created.otherAccount.number should equal(t.counterparty.get.account_number.get)

  }

  it should "allow counterparty name to be unspecified" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty
    val baseT = Extraction.decompose(t)
    val missingCounterpartNameTransaction = removeField(baseT, List("counterparty", "name"))

    getResponse(List(missingCounterpartNameTransaction)).code should equal(SUCCESS)

    //check it was created, name is generated, and account number matches
    val createdTransaction = Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
      AccountId(t.this_account.id),
      TransactionId(t.id))

    createdTransaction.isDefined should equal(true)
    val created = createdTransaction.get

    created.otherAccount.label.nonEmpty should equal(true)
    created.otherAccount.number should equal(t.counterparty.get.account_number.get)

  }

  it should "allow counterparty account number to be empty" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty
    val baseT = Extraction.decompose(t)
    val emptyCounterpartyAccountNumberTransaction = baseT.replace(List("counterparty", "account_number"), "")

    getResponse(List(emptyCounterpartyAccountNumberTransaction)).code should equal(SUCCESS)

    //check it was created, name matches, and account number is empty
    val createdTransaction = Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
      AccountId(t.this_account.id),
      TransactionId(t.id))

    createdTransaction.isDefined should equal(true)
    val created = createdTransaction.get

    created.otherAccount.label should equal(t.counterparty.get.name.get)
    created.otherAccount.number should equal("")
  }

  it should "allow counterparty account number to be unspecified" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty
    val baseT = Extraction.decompose(t)
    val missingCounterpartyAccountNumberTransaction = removeField(baseT, List("counterparty", "account_number"))

    getResponse(List(missingCounterpartyAccountNumberTransaction)).code should equal(SUCCESS)

    //check it was created, name matches, and account number is empty
    val createdTransaction = Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
      AccountId(t.this_account.id),
      TransactionId(t.id))

    createdTransaction.isDefined should equal(true)
    val created = createdTransaction.get

    created.otherAccount.label should equal(t.counterparty.get.name.get)
    created.otherAccount.number should equal("")
  }

  it should "allow counterparties with the same name to have different account numbers" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t1 = Extraction.decompose(transactionWithCounterparty)
    val t1Id = transactionWithCounterparty.id
    val t2Id = "t2Id"
    t2Id should not equal(t1Id)
    val c1 = transactionWithCounterparty.counterparty.get
    val counterparty2AccountNumber = c1.account_number.get + "2"

    val badT2 = t1.replace("id", t2Id).replace(List("counterparty", "account_number"), counterparty2AccountNumber)

    getResponse(List(t1, badT2)).code should equal(SUCCESS)

    val bankId = BankId(transactionWithCounterparty.this_account.bank)
    val accountId = AccountId(transactionWithCounterparty.this_account.id)

    def checkTransactionsCreated(created : Boolean) = {
      val foundTransaction1Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(t1Id))
      val foundTransaction2Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(t2Id))

      foundTransaction1Box.isDefined should equal(created)
      foundTransaction2Box.isDefined should equal(created)
    }

    checkTransactionsCreated(true)
  }

  it should "have transactions share counterparties if they are the same" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t1 = Extraction.decompose(transactionWithCounterparty)
    val t1Id = transactionWithCounterparty.id
    val t2Id = "t2Id"
    t2Id should not equal(t1Id)
    val t2 = replaceField(t1, "id", t2Id)

    val bankId = BankId(transactionWithCounterparty.this_account.bank)
    val accountId = AccountId(transactionWithCounterparty.this_account.id)

    getResponse(t1 :: t2 :: Nil).code should equal(SUCCESS)

    val foundTransaction1Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(t1Id))
    val foundTransaction2Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(t2Id))

    foundTransaction1Box.isDefined should equal(true)
    foundTransaction2Box.isDefined should equal(true)

    val counter1 = foundTransaction1Box.get.otherAccount
    val counter2 = foundTransaction2Box.get.otherAccount

    counter1.id should equal(counter2.id)
    counter1.metadata.getPublicAlias should equal(counter2.metadata.getPublicAlias)
  }

  it should "consider counterparties with the same name but different account numbers to be different" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val baseT = Extraction.decompose(transactionWithCounterparty)
    val counterAcc1 = "1"
    val counterAcc2 = "2"
    val id1 = "id1"
    val id2 = "id2"
    val t1 = baseT.replace(List("counterparty", "account_number"), counterAcc1).replace(List("id"), id1)
    val t2 = baseT.replace(List("counterparty", "account_number"), counterAcc2).replace(List("id"), id2)

    getResponse(t1 :: t2 :: Nil).code should equal(SUCCESS)

    val bankId = BankId(transactionWithCounterparty.this_account.bank)
    val accountId = AccountId(transactionWithCounterparty.this_account.id)
    val foundTransaction1Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(id1))
    val foundTransaction2Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(id2))

    foundTransaction1Box.isDefined should equal(true)
    foundTransaction2Box.isDefined should equal(true)

    val counter1 = foundTransaction1Box.get.otherAccount
    val counter2 = foundTransaction2Box.get.otherAccount

    counter1.id should not equal(counter2.id)
    counter1.metadata.getPublicAlias should not equal(counter2.metadata.getPublicAlias)
    counter1.number should equal(counterAcc1)
    counter2.number should equal(counterAcc2)
  }

  it should "consider counterparties without names but with the same account numbers to be the same" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val baseT = Extraction.decompose(transactionWithCounterparty)
    val t1 = removeField(baseT, List("counterparty", "name"))
    val id1 = transactionWithCounterparty.id
    val id2 = id1 + "--2"
    val t2 = removeField(t1.replace("id", id2), List("counterparty", "name"))

    getResponse(t1 :: t2 :: Nil).code should equal(SUCCESS)

    val bankId = BankId(transactionWithCounterparty.this_account.bank)
    val accountId = AccountId(transactionWithCounterparty.this_account.id)
    val foundTransaction1Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(id1))
    val foundTransaction2Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(id2))

    foundTransaction1Box.isDefined should equal(true)
    foundTransaction2Box.isDefined should equal(true)

    val counter1 = foundTransaction1Box.get.otherAccount
    val counter2 = foundTransaction2Box.get.otherAccount

    counter1.id should equal(counter2.id)
    counter1.metadata.getPublicAlias should equal(counter2.metadata.getPublicAlias)
    counter1.number should equal(transactionWithCounterparty.counterparty.get.account_number.get)
    counter2.number should equal(transactionWithCounterparty.counterparty.get.account_number.get)
  }

  it should "consider counterparties without names but with different account numbers to be different" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val id1 = "id1"
    val id2 = "id2"
    val counterpartyAccNumber1 = "1"
    val counterpartyAccNumber2 = "2"

    val baseT = transactionWithoutCounterparty
    val baseTransaction = Extraction.decompose(baseT)

    val t1 = baseTransaction.replace(List("id"), id1).replace(List("counterparty"), ("account_number" -> counterpartyAccNumber1))
    val t2 = baseTransaction.replace(List("id"), id2).replace(List("counterparty"), ("account_number" -> counterpartyAccNumber2))

    getResponse(List(t1, t2)).code should equal(SUCCESS)

    val bankId = BankId(baseT.this_account.bank)
    val accountId = AccountId(baseT.this_account.id)
    val foundTransaction1Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(id1))
    val foundTransaction2Box = Connector.connector.vend.getTransaction(bankId, accountId, TransactionId(id2))

    foundTransaction1Box.isDefined should equal(true)
    foundTransaction2Box.isDefined should equal(true)

    val counter1 = foundTransaction1Box.get.otherAccount
    val counter2 = foundTransaction2Box.get.otherAccount

    //transactions should have the same counterparty
    counter1.id should not equal(counter2.id)
    counter1.id.isEmpty should equal(false)
    counter2.id.isEmpty should equal(false)
    counter1.metadata.getPublicAlias should not equal(counter2.metadata.getPublicAlias)
    counter1.number should equal(counterpartyAccNumber1)
    counter2.number should equal(counterpartyAccNumber2)
  }

  it should "always create a new counterparty if none was specified, rather than having all transactions without specified" +
    "counterparties share a single one" in {

    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val id2 = "id2"
    id2 should not equal(transactionWithoutCounterparty.id)
    val anotherTransactionWithoutCounterparty = replaceField(Extraction.decompose(transactionWithoutCounterparty), "id", id2)

    val id3 = transactionWithCounterparty.id + "id3"
    val transactionWithBlankCounterparty = replaceField(Extraction.decompose(transactionWithoutCounterparty), "id", id3).replace(List("counterparty"), JNothing)

    val response = getResponse(anotherTransactionWithoutCounterparty :: transactionWithBlankCounterparty :: Extraction.decompose(transactionWithoutCounterparty) :: Nil)
    response.code should equal(SUCCESS)

    val accountId = AccountId(transactionWithoutCounterparty.this_account.id)
    val bankId = BankId(transactionWithoutCounterparty.this_account.bank)
    val tId1 = TransactionId(transactionWithoutCounterparty.id)
    val tId2 = TransactionId(id2)
    val tId3 = TransactionId(id3)

    val foundTransaction1Box = Connector.connector.vend.getTransaction(bankId, accountId, tId1)
    val foundTransaction2Box = Connector.connector.vend.getTransaction(bankId, accountId, tId2)
    val foundTransaction3Box = Connector.connector.vend.getTransaction(bankId, accountId, tId3)

    foundTransaction1Box.isDefined should equal(true)
    foundTransaction2Box.isDefined should equal(true)
    foundTransaction3Box.isDefined should equal(true)

    val counter1 = foundTransaction1Box.get.otherAccount
    val counter2 = foundTransaction2Box.get.otherAccount
    val counter3 = foundTransaction3Box.get.otherAccount

    counter1.id should not equal(counter2.id)
    counter1.id should not equal(counter3.id)
    counter2.id should not equal(counter3.id)
    counter1.metadata.getPublicAlias should not equal(counter2.metadata.getPublicAlias)
    counter1.metadata.getPublicAlias should not equal(counter3.metadata.getPublicAlias)
    counter2.metadata.getPublicAlias should not equal(counter3.metadata.getPublicAlias)
  }

  it should "not create any transactions when one has an invalid or missing value" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty

    val validTransaction = Extraction.decompose(t)

    val newTransId = "0239403294322343"
    newTransId should not equal(t.id)

    val baseNewTransaction = replaceField(validTransaction, "id", newTransId)

    val transactionWithoutValue = removeField(baseNewTransaction, List("details", "value"))

    //shouldn't work
    getResponse(List(validTransaction, transactionWithoutValue)).code should equal(FAILED)

    def checkNoTransactionsExist() = checkTransactions(false)
    def checkTransactionsExist() = checkTransactions(true)

    def checkTransactions(exist: Boolean) = {
      Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
        AccountId(t.this_account.id),
        TransactionId(t.id)).isDefined should equal(exist)

      Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
        AccountId(t.this_account.id),
        TransactionId(newTransId)).isDefined should equal(exist)
    }

    //no transactions should be created
    checkNoTransactionsExist()

    //check transaction with bad value
    val transactionWithBadValue = baseNewTransaction.replace(List("details", "value"), "ABCD")

    //shouldn't work
    getResponse(List(validTransaction, transactionWithBadValue)).code should equal(FAILED)
    checkNoTransactionsExist()

    //now make sure it works with a good value
    val transactionWithGoodValue = baseNewTransaction.replace(List("details", "value"), "-34.65")

    getResponse(List(validTransaction, transactionWithGoodValue)).code should equal(SUCCESS)
    checkTransactionsExist()
  }

  it should "not create any transactions when one has an invalid or missing completed date" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty

    val validTransaction = Extraction.decompose(t)

    val newTransId = "0239403294322343"
    newTransId should not equal(t.id)

    def checkNoTransactionsExist() = checkTransactions(false)
    def checkTransactionsExist() = checkTransactions(true)

    def checkTransactions(exist: Boolean) = {
      Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
        AccountId(t.this_account.id),
        TransactionId(t.id)).isDefined should equal(exist)

      Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
        AccountId(t.this_account.id),
        TransactionId(newTransId)).isDefined should equal(exist)
    }

    val baseNewTransaction = validTransaction.replace("id", newTransId)

    val transactionWithMissingCompleted = removeField(baseNewTransaction, List("details", "completed"))

    //shouldn't work
    getResponse(List(validTransaction, transactionWithMissingCompleted)).code should equal(FAILED)
    checkNoTransactionsExist()

    //check transaction with bad completed date
    val transactionWithBadCompleted = baseNewTransaction.replace(List("details", "completed"), "ASDF")

    //shouldn't work
    getResponse(List(validTransaction, transactionWithBadCompleted)).code should equal(FAILED)
    checkNoTransactionsExist()

    //now make sure it works with a valid completed date
    val transactionWithGoodcompleted = baseNewTransaction.replace(List("details", "completed"), "2016-11-07T05:25:33.001Z")

    //should work
    getResponse(List(validTransaction, transactionWithGoodcompleted)).code should equal(SUCCESS)
    checkTransactionsExist()
  }

  it should "check that counterparty specified is not generated if it already exists (for the original account in question)" in {
    val (banks, users, accounts) = (standardBanks, standardUsers, standardAccounts)

    def getResponse(transactionJsons : List[JValue]) = {
      val json = createImportJson(banks.map(Extraction.decompose),
        users.map(Extraction.decompose), accounts.map(Extraction.decompose), transactionJsons)
      postImportJson(json)
    }

    val t = transactionWithCounterparty

    val validTransaction = Extraction.decompose(t)

    val newTransId = "0239403294322343"
    newTransId should not equal(t.id)

    val transactionWithSameCounterparty = replaceField(validTransaction, "id", newTransId)

    getResponse(List(validTransaction, transactionWithSameCounterparty)).code should equal(SUCCESS)

    def getCreatedTransaction(id : String) =
      Connector.connector.vend.getTransaction(BankId(t.this_account.bank),
        AccountId(t.this_account.id),
        TransactionId(id)).get

    val t1 = getCreatedTransaction(t.id)
    val t2 = getCreatedTransaction(newTransId)

    //check the created transactions have the same counterparty id
    t1.otherAccount.id should equal(t2.otherAccount.id)
  }

}
