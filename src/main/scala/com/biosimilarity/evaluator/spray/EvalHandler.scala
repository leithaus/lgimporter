// -*- mode: Scala;-*- 
// Filename:    EvalHandler.scala 
// Authors:     lgm                                                    
// Creation:    Wed May 15 13:53:55 2013 
// Copyright:   Not supplied 
// Description: 
// ------------------------------------------------------------------------

package com.biosimilarity.evaluator.spray

import com.protegra_ati.agentservices.store._
import com.protegra_ati.agentservices.protocols.msgs._

import com.biosimilarity.evaluator.distribution._
import com.biosimilarity.evaluator.msgs._
import com.biosimilarity.lift.model.store._
import com.biosimilarity.lift.lib._

import akka.actor._
import spray.routing._
//import directives.CompletionMagnet
import spray.http._
import spray.http.StatusCodes._
import MediaTypes._

import spray.httpx.encoding._

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.JsonDSL._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.continuations._
import scala.collection.mutable.HashMap

import com.typesafe.config._

import javax.crypto._
import javax.crypto.spec._
import java.security._

import java.util.Date
import java.util.UUID

import java.net.URI

// Mask the json4s symbol2jvalue implicit so we can use the PrologDSL
object symbol2jvalue extends Serializable {}

object CompletionMapper extends Serializable {
  @transient
  val map = new HashMap[String, RequestContext]()
  def complete(key: String, message: String): Unit = {
    for (reqCtx <- map.get(key)) {
      reqCtx.complete(HttpResponse(200, message))
    }
    map -= key
  }
}

object CometActorMapper extends Serializable {
  @transient
  val map = new HashMap[String, akka.actor.ActorRef]()
  val key = ""
  def cometMessage(sessionURI: String, jsonBody: String): Unit = {
    //println("cometMessage: "+ List(sessionURI, jsonBody))
    for (cometActor <- map.get(key)) {
      cometActor ! CometMessage(sessionURI, jsonBody)
    }
  }
}

object btcKeyMapper extends Serializable {
  @transient
  val map = new HashMap[String, String]()
}

object ConfirmationEmail extends Serializable {
  def confirm(email: String, token: String) = {
    import org.apache.commons.mail._
    val simple = new SimpleEmail()
    simple.setHostName("smtp.googlemail.com")
    simple.setSmtpPort(465)
    //simple.setAuthenticator(new DefaultAuthenticator("individualagenttech", "4genttech"))
    simple.setAuthenticator(new DefaultAuthenticator("splicious.ftw", "spl1c1ous"))
    simple.setSSLOnConnect(true)
    //simple.setFrom("individualagenttech@gmail.com")
    simple.setFrom("splicious.ftw@gmail.com")
    simple.setSubject("Confirm splicious agent signup")
    // TODO(mike): get the URL from a config file
    simple.setMsg("""Your token is: """ + token)
    simple.addTo(email)
    simple.send()
  }
}

trait CapUtilities {
  // Compute the mac of an email address
  def emailToCap(email: String): String = {
    val macInstance = Mac.getInstance("HmacSHA256")
    macInstance.init(new SecretKeySpec("emailmac".getBytes("utf-8"), "HmacSHA256"))
    macInstance.doFinal(email.getBytes("utf-8")).map("%02x" format _).mkString.substring(0, 36)
  }
  def splEmail(email: String): String = {
    val spliciousBTCWalletCap =
      emailToCap(email)
    spliciousBTCWalletCap + "@splicious.net"
  }
  def pw(email: String, password: String): String = {
    val spliciousEmail = splEmail(email)
    val spliciousBTCWalletCap = spliciousEmail.split("@")(0)
    emailToCap(spliciousBTCWalletCap + password + "@splicious.net")
  }
}

trait BTCCryptoUtilities {
  import java.security.SecureRandom
  import java.security.MessageDigest

  def hexStringToByteArray(s: String) = {
    val len = s.length();
    var data = new Array[Byte](len / 2)
    var i = 0

    while (i < len) {
      val b = (Character.digit(s.charAt(i), 16) << 4) +
        (Character.digit(s.charAt(i + 1), 16))

      data(i / 2) = b.asInstanceOf[Byte]

      i += 2
    }

    data
  }

  def generateRandomHex(): String = {
    val result = new Array[Byte](32)
    val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
    random.nextBytes(result)
    result.map("%02x".format(_)).mkString
  }

  def hashSaltedPasswordTwiceWithSHA256(password: String, salt: Array[Byte]): Array[Byte] = {
    // Hash the salted pw twice with sha256
    val sha256 = MessageDigest.getInstance("SHA-256");
    sha256.digest(sha256.digest(password.getBytes("UTF-8") ++ salt))
  }

  def saltedPasswordHelper(password: String, salt: Array[Byte], data: Array[Byte], mode: Int): Array[Byte] = {
    val hash = hashSaltedPasswordTwiceWithSHA256(password, salt)

    // Create a keyspec from the hash
    val keySpec = new SecretKeySpec(hash, "AES")

    // Use a zero IV; 
    val iv = Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val ivSpec = new IvParameterSpec(iv)

    // Encrypt or decrypt the data with the pw-derived key
    val aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
    aes.init(mode, keySpec, ivSpec);
    aes.doFinal(data);
  }

  def encryptWithSaltedPassword(password: String, data: Array[Byte]): Array[Byte] = {
    // Generate random 4 bytes of salt
    val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
    val salt = new Array[Byte](4)
    random.nextBytes(salt)
    salt ++ saltedPasswordHelper(password, salt, data, Cipher.ENCRYPT_MODE)
  }

  def decryptWithSaltedPassword(password: String, data: Array[Byte]): Array[Byte] = {
    // Separate salt from encrypted data
    val salt = data.dropRight(4)
    val encrypted = data.drop(data.length - 4)

    saltedPasswordHelper(password, salt, encrypted, Cipher.DECRYPT_MODE)
  }

  def generateWIFKey(): String = {
    import scala.math.BigInt

    // Disable this implicit conversion so I can treat a string like an array
    val string2jvalue = None;

    val bytes = new Array[Byte](33)
    val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
    do {
      random.nextBytes(bytes)
    } while (bytes(1) == 255 && bytes(2) == 255 && bytes(3) == 255 && bytes(4) == 255)
    bytes(0) = -128
    val sha256 = MessageDigest.getInstance("SHA-256");
    val digest1 = sha256.digest(bytes)
    val digest2 = sha256.digest(digest1)
    val result = new Array[Byte](37)
    bytes.copyToArray(result)
    digest2.copyToArray(result, 33, 4)
    val table = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var big = BigInt.apply(result.map("%02x".format(_)).mkString, 16)
    var out = ""
    do {
      val (b, r) = big /% 58
      out = table(r.intValue) + out
      big = b
    } while (big > 0)
    out
  }
}

trait EvalHandler extends CapUtilities with BTCCryptoUtilities {
  self: EvaluationCommsService with DownStreamHttpCommsT =>

  import DSLCommLink.mTT
  import ConcreteHL._
  import BlockChainAPI._

  @transient
  implicit val formats = DefaultFormats

  // Setup
  val userPWDBLabel = fromTermString("""pwdb(Salt, Hash, "user", K)""").
    getOrElse(throw new Exception("Couldn't parse label."))
  val adminPWDBLabel = fromTermString("""pwdb(Salt, Hash, "admin", K)""").
    getOrElse(throw new Exception("Couldn't parse label."))

  def toHex(bytes: Array[Byte]): String = {
    bytes.map("%02X" format _).mkString
  }

  /**
   * Creates an agent.
   *
   * @param json input JSON for agent
   * @param key this is used to identify a return request (optional)
   */
  def createAgentRequest(json: JValue, key: String): Unit = {
    try {
      val authType = (json \ "content" \ "authType").extract[String].toLowerCase
      if (authType != "password") {
        createAgentError(key, "Only password authentication is currently supported.")
      } else {
        val authValue = (json \ "content" \ "authValue").extract[String]
        val (salt, hash) = saltAndHash(authValue)

        // TODO(mike): explicitly manage randomness pool
        val rand = new SecureRandom()
        val bytes = new Array[Byte](16)

        // Generate random Agent URI
        rand.nextBytes(bytes)
        val uri = new URI("agent://" + toHex(bytes))
        val agentIdCnxn = PortableAgentCnxn(uri, "identity", uri)

        // Generate K for encrypting the lists of aliases, external identities, etc. on the Agent
        // term = pwdb(<salt>, hash = SHA(salt + pw), "user", AES_hash(K)) 
        // post term.toString on (Agent, term)
        {
          // Since we're encrypting exactly 128 bits, ECB is OK
          val aes = Cipher.getInstance("AES/ECB/NoPadding")
          aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hash, "AES"))
          // Generate K
          rand.nextBytes(bytes)
          // AES_hash(K)
          val aesHashK = toHex(aes.doFinal(bytes))

          //val (erql, erspl) = agentMgr().makePolarizedPair()
          //agentMgr().post(erql, erspl)(
          post(
            userPWDBLabel,
            List(agentIdCnxn),
            // TODO(mike): do proper context-aware interpolation
            "pwdb(" + List(salt, toHex(hash), "user", aesHashK).map('"' + _ + '"').mkString(",") + ")",
            (optRsrc: Option[mTT.Resource]) => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "createAgentResponse") ~
                  ("content" -> (
                    "agentURI" -> uri.toString)))))
            })
        }
      }
    } catch {
      case e: Exception => {
        createAgentError(key, e.toString)
      }
    }
  }
  def createAgentError(key: String, reason: String): Unit = {
    CompletionMapper.complete(key, compact(render(
      ("msgType" -> "createAgentError") ~
        ("content" -> (
          "reason" -> reason)))))
  }
  def saltAndHash(pw: String): (String, Array[Byte]) = {
    val md = MessageDigest.getInstance("SHA1")
    val salt = UUID.randomUUID.toString.substring(0, 8)
    md.update(salt.getBytes("utf-8"))
    md.update(pw.getBytes("utf-8"))
    (salt, md.digest)
  }

  // Why is this here in an enclosing scope of EvaluationCommsService?
  // Also why not use the EvalHandlerService?
  @transient
  object handler extends EvalConfig
    with DSLCommLinkConfiguration
    with AccordionConfiguration
    with EvaluationCommsService
    with AgentCRUDHandler
    with AgentIntroductionHandler
    with Serializable {}

  // Agents
  def addAgentExternalIdentityRequest(json: JValue): Unit = {}
  def addAgentExternalIdentityToken(json: JValue): Unit = {}
  def removeAgentExternalIdentitiesRequest(json: JValue): Unit = {}
  def getAgentExternalIdentitiesRequest(json: JValue): Unit = {}
  def addAgentAliasesRequest(json: JValue): Unit = {
    handler.handleaddAgentAliasesRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.addAgentAliasesRequest(
        new URI((json \ "content" \ "sessionURI").extract[String]),
        (json \ "content" \ "aliases").extract[List[String]]))
  }
  def removeAgentAliasesRequest(json: JValue): Unit = {
    handler.handleremoveAgentAliasesRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.removeAgentAliasesRequest(
        new URI((json \ "content" \ "sessionURI").extract[String]),
        (json \ "content" \ "aliases").extract[List[String]]))
  }
  def getAgentAliasesRequest(json: JValue): Unit = {
    handler.handlegetAgentAliasesRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.getAgentAliasesRequest(
        new URI((json \ "content" \ "sessionURI").extract[String])))
  }
  def getDefaultAliasRequest(json: JValue): Unit = {
    handler.handlegetDefaultAliasRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.getDefaultAliasRequest(
        new URI((json \ "content" \ "sessionURI").extract[String])))
  }
  def setDefaultAliasRequest(json: JValue): Unit = {
    handler.handlesetDefaultAliasRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.setDefaultAliasRequest(
        new URI((json \ "content" \ "sessionURI").extract[String]),
        (json \ "content" \ "alias").extract[String]))
  }
  // Aliases
  def addAliasExternalIdentitiesRequest(json: JValue): Unit = {}
  def removeAliasExternalIdentitiesRequest(json: JValue): Unit = {}
  def getAliasExternalIdentitiesRequest(json: JValue): Unit = {}
  def setAliasDefaultExternalIdentityRequest(json: JValue): Unit = {}
  // Connections
  case class JCnxn(source: String, label: String, target: String)
  def removeAliasConnectionsRequest(json: JValue): Unit = {
    val sessionURIStr = (json \ "content" \ "sessionURI").extract[String]
    val jcnxns = (json \ "content" \ "connections").asInstanceOf[JArray].arr
    handler.handleremoveAliasConnectionsRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.removeAliasConnectionsRequest(
        new URI(sessionURIStr),
        (json \ "content" \ "alias").extract[String],
        jcnxns.map((c: JValue) => PortableAgentCnxn(
          new URI((c \ "source").extract[String]),
          (c \ "label").extract[String],
          new URI((c \ "target").extract[String])))))
  }
  def getAliasConnectionsRequest(json: JValue): Unit = {
    val sessionURIStr = (json \ "content" \ "sessionURI").extract[String]
    handler.handlegetAliasConnectionsRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.getAliasConnectionsRequest(
        new URI(sessionURIStr),
        (json \ "content" \ "alias").extract[String]))
  }
  // Labels
  def addAliasLabelsRequest(json: JValue): Unit = {
    val sessionURIStr = (json \ "content" \ "sessionURI").extract[String]
    handler.handleaddAliasLabelsRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.addAliasLabelsRequest(
        new URI(sessionURIStr),
        (json \ "content" \ "alias").extract[String],
        (json \ "content" \ "labels").extract[List[String]].
          map(fromTermString).
          map(_.getOrElse(
            CometActorMapper.cometMessage(sessionURIStr, compact(render(
              ("msgType" -> "addAliasLabelsError") ~
                ("content" -> ("reason" -> ("Couldn't parse a label:" +
                  compact(render(json \ "content" \ "labels")))))))))).asInstanceOf[List[CnxnCtxtLabel[String, String, String]]]))
  }
  def updateAliasLabelsRequest(json: JValue): Unit = {
    val sessionURIStr = (json \ "content" \ "sessionURI").extract[String]
    handler.handleupdateAliasLabelsRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.updateAliasLabelsRequest(
        new URI(sessionURIStr),
        (json \ "content" \ "alias").extract[String],
        (json \ "content" \ "labels").extract[List[String]].
          map(fromTermString).
          map(_.getOrElse(
            CometActorMapper.cometMessage(sessionURIStr, compact(render(
              ("msgType" -> "updateAliasLabelsError") ~
                ("content" -> ("reason" -> ("Couldn't parse a label:" +
                  compact(render(json \ "content" \ "labels")))))))))).asInstanceOf[List[CnxnCtxtLabel[String, String, String]]]))
  }
  def getAliasLabelsRequest(json: JValue): Unit = {
    val sessionURIStr = (json \ "content" \ "sessionURI").extract[String]
    handler.handlegetAliasLabelsRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.getAliasLabelsRequest(
        new URI(sessionURIStr),
        (json \ "content" \ "alias").extract[String]))
  }
  def setAliasDefaultLabelRequest(json: JValue): Unit = {}
  def getAliasDefaultLabelRequest(json: JValue): Unit = {}
  // DSL
  def evalSubscribeCancelRequest(json: JValue): Unit = {
    BasicLogService.tweet("evalSubscribeCancelRequest: json = " + compact(render(json)))
    val sessionURIStr = (json \ "content" \ "sessionURI").extract[String]
    val jcnxns = (json \ "content" \ "connections").asInstanceOf[JArray].arr
    handler.handleevalSubscribeCancelRequest(
      com.biosimilarity.evaluator.msgs.agent.crud.evalSubscribeCancelRequest(
        new URI(sessionURIStr),
        new SumOfProducts()((json \ "content" \ "filter").extract[String]),
        jcnxns.map((c: JValue) => PortableAgentCnxn(
          new URI((c \ "source").extract[String]),
          (c \ "label").extract[String],
          new URI((c \ "target").extract[String])))))
  }
  // Introduction Protocol
  def beginIntroductionRequest(json: JValue): Unit = {
    handler.handlebeginIntroductionRequest(
      com.protegra_ati.agentservices.msgs.agent.introduction.beginIntroductionRequest(
        new URI((json \ "content" \ "sessionURI").extract[String]),
        (json \ "content" \ "alias").extract[String],
        new PortableAgentCnxn(
          new URI((json \ "content" \ "aConnection" \ "source").extract[String]),
          (json \ "content" \ "aConnection" \ "label").extract[String],
          new URI((json \ "content" \ "aConnection" \ "target").extract[String])),
        new PortableAgentCnxn(
          new URI((json \ "content" \ "bConnection" \ "source").extract[String]),
          (json \ "content" \ "bConnection" \ "label").extract[String],
          new URI((json \ "content" \ "bConnection" \ "target").extract[String])),
        (json \ "content" \ "aMessage").extract[String],
        (json \ "content" \ "bMessage").extract[String]))
  }
  def introductionConfirmationRequest(json: JValue): Unit = {
    handler.handleintroductionConfirmationRequest(
      com.protegra_ati.agentservices.msgs.agent.introduction.introductionConfirmationRequest(
        new URI((json \ "content" \ "sessionURI").extract[String]),
        (json \ "content" \ "alias").extract[String],
        (json \ "content" \ "introSessionId").extract[String],
        (json \ "content" \ "correlationId").extract[String],
        (json \ "content" \ "accepted").extract[Boolean]))
  }

  // ----------------------------------------------------------------------------------------------------------
  // Agent signon schema
  // ----------------------------------------------------------------------------------------------------------
  val jsonBlobLabel = fromTermString("jsonBlob(W)").getOrElse(throw new Exception("Couldn't parse jsonBlobLabel"))
  val pwmacLabel = fromTermString("pwmac(X)").getOrElse(throw new Exception("Couldn't parse pwmacLabel"))
  val emailLabel = fromTermString("email(Y)").getOrElse(throw new Exception("Couldn't parse emailLabel"))
  val tokenLabel = fromTermString("token(Z)").getOrElse(throw new Exception("Couldn't parse tokenLabel"))
  val aliasListLabel = fromTermString("aliasList(true)").getOrElse(throw new Exception("Couldn't parse aliasListLabel"))
  val defaultAliasLabel = fromTermString("defaultAlias(true)").getOrElse(throw new Exception("Couldn't parse defaultAlias"))
  val labelListLabel = fromTermString("labelList(true)").getOrElse(throw new Exception("Couldn't parse labelListLabel"))
  val biCnxnsListLabel = fromTermString("biCnxnsList(true)").getOrElse(throw new Exception("Couldn't parse biCnxnsListLabel"))
  val btcWalletLabel = fromTermString("btc(walletRequest(W))").getOrElse(throw new Exception("Couldn't parse btc(W)."))
  val btcWalletJSONLabel =
    fromTermString("btc(walletData(W))").getOrElse(throw new Exception("Couldn't parse btc( WalletRequest( W ) )."))
  val btcWIFKeyLongTermStorage =
    fromTermString("btc(wifKey(W))").getOrElse(throw new Exception("Couldn't parse btc( wifKey( W ) )."))

  def confirmEmailToken(json: JValue, key: String): Unit = {
    val token = (json \ "content" \ "token").extract[String]
    val tokenUri = new URI("token://" + token)
    val tokenCnxn = PortableAgentCnxn(tokenUri, "token", tokenUri)

    def handleRsp(v: ConcreteHL.HLExpr): Unit = {
      v match {
        case Bottom => {
          CompletionMapper.complete(key, compact(render(
            ("msgType" -> "createUserError") ~
              ("content" ->
                ("reason", "No such token.")))))
        }
        case PostedExpr((PostedExpr(postedStr: String), _, _, _)) => {
          val content = parse(postedStr)
          val email = (content \ "email").extract[String]
          val password = (content \ "password").extract[String]
          val jsonBlob = compact(render(content \ "jsonBlob"))
          val createBTCWallet = (content \ "createBTCWallet").extract[Boolean]
          val btcWalletAddress =
            if (!createBTCWallet) {
              Some((content \ "btcWalletAddress").extract[String])
            } else {
              None
            }
          secureSignup(email, password, jsonBlob, key, btcWalletAddress)
        }
      }
    }
    read(tokenLabel, List(tokenCnxn), (rsrc: Option[mTT.Resource]) => {
      rsrc match {
        case None => ();
        case Some(mTT.Ground(v)) => {
          handleRsp(v)
        }
        case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
          handleRsp(v)
        }
        case _ => throw new Exception("Unrecognized resource: " + rsrc)
      }
    })
  }

  // Given an email, mac it, then create Cnxn(mac, "emailhash", mac) and post "email(X): mac"
  // to show we know about the email.  Return the mac
  def storeCapByEmail(email: String): String = {
    val cap = emailToCap(email)
    val emailURI = new URI("emailhash://" + cap)
    val emailSelfCnxn = //new ConcreteHL.PortableAgentCnxn(emailURI, emailURI.toString, emailURI)
      PortableAgentCnxn(emailURI, "emailhash", emailURI)
    put[String](
      emailLabel,
      List(emailSelfCnxn),
      cap)
    cap
  }

  def doCreateBTCWallet(
    aliasCnxn: PortableAgentCnxn,
    email: String,
    password: String,
    uri: String = "https://blockchain.info/api/v2/create_wallet"): Unit = {
    val spliciousEmail = splEmail(email)
    //val spliciousSaltedPwd = pw( email, password )
    val spliciousSaltedPwd = pw(email, "")

    // Generate wallet key
    val btcWIFKey = generateWIFKey
    println("doCreateBTCWallet: Generated " + btcWIFKey)

    // BUGBUG : lgm -- fix the padding and uncomment these lines
    //val encryptedWIFKey = encryptWithSaltedPassword(password, btcWIFKey.getBytes("UTF-8")).map("%02x".format(_)).mkString

    post(
      btcWIFKeyLongTermStorage,
      List(aliasCnxn),
      "", //encryptedWIFKey,
      (optRsrc: Option[mTT.Resource]) => println("WIFKey encrypted and stored: " + optRsrc + ", " + "encryptedKey" /*  encryptedWIFKey */ ))

    val cwd = CreateWalletData(
      spliciousSaltedPwd,
      createWalletAPICode,
      btcWIFKey,
      "splicious",
      spliciousEmail)
    val cw = CreateWallet(cwd, new java.net.URL(uri))
    ask(
      aliasCnxn,
      btcWalletLabel,
      cw,
      (optRsrc: Option[mTT.Resource]) => println("blockchain response: " + optRsrc))

    def handleRsp(v: ConcreteHL.HLExpr): Unit = {
      v match {
        case Bottom => {
          println(
            (
              "*********************************************************************************"
              + "\nwaiting for btc json data"
              + "\naliasCnxn: " + aliasCnxn
              + "\nbtcWalletLabel: " + btcWalletLabel
              + "\n*********************************************************************************"))
          BasicLogService.tweet(
            (
              "*********************************************************************************"
              + "\nwaiting for btc json data"
              + "\naliasCnxn: " + aliasCnxn
              + "\nbtcWalletLabel: " + btcWalletLabel
              + "\n*********************************************************************************"))
        }
        case PostedExpr((PostedExpr(btcWalletJsonStr: String), _, _, _)) => {
          println(
            (
              "*********************************************************************************"
              + "\nreceived btc json data"
              + "\naliasCnxn: " + aliasCnxn
              + "\nbtcWalletLabel: " + btcWalletLabel
              + "\nbtcWalletJSONStr: " + btcWalletJsonStr
              + "\nbtcWalletJSONStrFixed: " + btcWalletJsonStr.replace("\\/", "/").replace("https:", "https%3a")
              + "\n*********************************************************************************"))
          BasicLogService.tweet(
            (
              "*********************************************************************************"
              + "\nreceived btc json data"
              + "\naliasCnxn: " + aliasCnxn
              + "\nbtcWalletLabel: " + btcWalletLabel
              + "\nbtcWalletJSONStr: " + btcWalletJsonStr
              + "\nbtcWalletJSONStrFixed: " + btcWalletJsonStr.replace("\\/", "/")
              + "\n*********************************************************************************"))
          //           val btcWalletJsonStrFixed = btcWalletJsonStr.replace( "\\/", "/" ).replace( "https:","https%3a" )
          //           BUGBUG : LGM -- The string is failing to parse; so, we'll
          //           try it another way
          //           val btcWalletJson = parse( btcWalletJsonStrFixed )
          //           val btcGuid = ( btcWalletJson \ "guid" ).extract[String]
          //           val btcAddress = ( btcWalletJson \ "address" ).extract[String]
          //           val btcLink = ( btcWalletJson \ "link" ).extract[String]
          try {
            val btcWalletJSONPairs =
              btcWalletJsonStr.replace("{", "").replace("}", "").replace("https:", "https_").split(",").map(_.split(":"))
            val btcGuid = btcWalletJSONPairs(0)(1)
            val btcAddress = btcWalletJSONPairs(1)(1)
            val btcLink = btcWalletJSONPairs(2)(1).replace("https_", "https:")

            val btcWalletTermStr =
              s"""btc( wallet( guid( ${btcGuid} ), address( ${btcAddress} ), link( ${btcLink} ) ) )"""

            val btcWalletAddressTermStr =
              s"""btc( walletAddress( ${btcAddress} ) )"""

            val cwrsp =
              CreateWalletResponse(
                btcGuid, btcAddress, btcLink)

            println(
              (
                "*********************************************************************************"
                + "\nreceived btc json data"
                + "\naliasCnxn: " + aliasCnxn
                + "\nbtcWalletLabel: " + btcWalletLabel
                + "\nbtcWalletTermStr: " + btcWalletTermStr
                + "\n*********************************************************************************"))
            BasicLogService.tweet(
              (
                "*********************************************************************************"
                + "\nreceived btc json data"
                + "\naliasCnxn: " + aliasCnxn
                + "\nbtcWalletLabel: " + btcWalletLabel
                + "\nbtcWalletTermStr: " + btcWalletTermStr
                + "\n*********************************************************************************"))

            val btcWalletTerm =
              fromTermString(
                btcWalletTermStr).getOrElse(throw new Exception("Couldn't parse ${btcWalletTermStr}."))

            val btcWalletAddressTerm =
              fromTermString(
                btcWalletAddressTermStr).getOrElse(throw new Exception("Couldn't parse ${btcWalletTermStr}."))

            println(
              (
                "*********************************************************************************"
                + "\nposting blockchain btc wallet data"
                + "\naliasCnxn: " + aliasCnxn
                + "\nbtcWalletLabel: " + btcWalletLabel
                + "\nbtcWalletTermStr: " + btcWalletTermStr
                + "\n*********************************************************************************"))
            BasicLogService.tweet(
              (
                "*********************************************************************************"
                + "\nposting blockchain btc wallet data"
                + "\naliasCnxn: " + aliasCnxn
                + "\nbtcWalletLabel: " + btcWalletLabel
                + "\nbtcWalletTermStr: " + btcWalletTermStr
                + "\n*********************************************************************************"))

            //             post(
            //               btcWalletTerm,
            //               List( aliasCnxn ),
            //               btcWalletJsonStr,
            //               ( optRsrc : Option[mTT.Resource] ) => println( "blockchain data stored: " + optRsrc )
            //             )

            post(
              btcWalletTerm,
              List(aliasCnxn),
              cwrsp,
              (optRsrc: Option[mTT.Resource]) => println("blockchain data reformated and stored: " + optRsrc))
          } catch {
            case e: Throwable => {
              e.printStackTrace
            }
          }
        }
        case _ => {
          println(
            (
              "*********************************************************************************"
              + "\nunexpected btc json data format" + v
              + "\naliasCnxn: " + aliasCnxn
              + "\nbtcWalletLabel: " + btcWalletLabel
              + "\n*********************************************************************************"))
          BasicLogService.tweet(
            (
              "*********************************************************************************"
              + "\nunexpected btc json data format" + v
              + "\naliasCnxn: " + aliasCnxn
              + "\nbtcWalletLabel: " + btcWalletLabel
              + "\n*********************************************************************************"))
          throw new Exception("unexpected btc json data format" + v)
        }
      }
    }

    def onWalletData(optRsrc: Option[mTT.Resource]): Unit = {
      optRsrc match {
        case None => ();
        case Some(mTT.Ground(v)) => {
          handleRsp(v)
        }
        case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
          handleRsp(v)
        }
        case _ => throw new Exception("Unexpected resource: " + optRsrc)
      }
    }

    println(
      (
        "*********************************************************************************"
        + "\ngetting Blockchain wallet data "
        + "\naliasCnxn: " + aliasCnxn
        + "\nbtcWalletLabel: " + btcWalletLabel
        + "\n*********************************************************************************"))
    get(btcWalletLabel, List(aliasCnxn), onWalletData)
  }

  def secureSignup(
    email: String,
    password: String,
    jsonBlob: String,
    key: String,
    //createBTCWallet : Boolean = false,
    btcWalletAddress: Option[String] = None): Unit = {
    import DSLCommLink.mTT
    val cap = if (email == "") UUID.randomUUID.toString else storeCapByEmail(email)
    BasicLogService.tweet("secureSignup email=" + email + ",password=" + password + ", cap=" + cap + ", btcWalletAddress=" + btcWalletAddress)
    val macInstance = Mac.getInstance("HmacSHA256")
    // TODO: Pull secrets out into config file
    macInstance.init(new SecretKeySpec("5ePeN42X".getBytes("utf-8"), "HmacSHA256"))
    val mac = macInstance.doFinal(cap.getBytes("utf-8")).slice(0, 5).map("%02x" format _).mkString
    val capAndMac = cap + mac
    val capURI = new URI("agent://" + cap)
    val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)

    macInstance.init(new SecretKeySpec("pAss#4$#".getBytes("utf-8"), "HmacSHA256"))
    val pwmac = macInstance.doFinal(password.getBytes("utf-8")).map("%02x" format _).mkString

    BasicLogService.tweet("secureSignup posting pwmac")

    val createUserResponse: Unit => Unit = Unit => {
      CompletionMapper.complete(key, compact(render(
        ("msgType" -> "createUserResponse") ~
          ("content" -> ("agentURI" -> ("agent://cap/" + capAndMac))))))
    }

    val onPost5 = (aliasCnxn: PortableAgentCnxn) => (optRsrc: Option[mTT.Resource]) => {
      BasicLogService.tweet("secureSignup onPost4: optRsrc = " + optRsrc)
      optRsrc match {
        case None => ()
        case Some(_) => {
          // Make the call to BTC wallet creation
          // emailToCap( email )@splicious.com 
          // will be used for the BlockChain call
          btcWalletAddress match {
            case None => doCreateBTCWallet(aliasCnxn, email, password)
            case Some(addr) => {
            }
          }

          onAgentCreation(
            cap,
            aliasCnxn,
            createUserResponse)
        }
      }
    }

    val onPost4 = (optRsrc: Option[mTT.Resource]) => {
      BasicLogService.tweet("secureSignup onPost3: optRsrc = " + optRsrc)
      optRsrc match {
        case None => ()
        case Some(_) => {
          val aliasCnxn = PortableAgentCnxn(capURI, "alias", capURI)
          //agentMgr().post(
          post(
            labelListLabel,
            List(aliasCnxn),
            """[]""",
            onPost5(aliasCnxn))
        }
      }
    }

    val onPost3 = (optRsrc: Option[mTT.Resource]) => {
      BasicLogService.tweet("secureSignup onPost2: optRsrc = " + optRsrc)
      optRsrc match {
        case None => ()
        case Some(_) => {
          post(
            defaultAliasLabel,
            List(capSelfCnxn),
            """alias""",
            onPost4)
        }
      }
    }

    val onPost2 = (optRsrc: Option[mTT.Resource]) => {
      BasicLogService.tweet("secureSignup onPost2: optRsrc = " + optRsrc)
      optRsrc match {
        case None => ()
        case Some(_) => {
          post(
            aliasListLabel,
            List(capSelfCnxn),
            """["alias"]""",
            onPost3)
        }
      }
    }

    val onPost1 = (optRsrc: Option[mTT.Resource]) => {
      BasicLogService.tweet("secureSignup onPost1: optRsrc = " + optRsrc)
      optRsrc match {
        case None => ()
        case Some(_) => {
          post(
            jsonBlobLabel,
            List(capSelfCnxn),
            jsonBlob,
            onPost2)
        }
      }
    }

    post(
      pwmacLabel,
      List(capSelfCnxn),
      pwmac,
      onPost1)
  }

  // TODO: Replace function below with behavior
  def listenIntroductionNotification(sessionURIStr: String, aliasCnxn: PortableAgentCnxn): Unit = {
    import com.protegra_ati.agentservices.protocols.msgs.IntroductionNotification

    val introNotificationLabel = IntroductionNotification.toLabel()
    def handleRsp(v: ConcreteHL.HLExpr): Unit = {
      v match {
        case PostedExpr((PostedExpr(IntroductionNotification(sessionId, correlationId, PortableAgentBiCnxn(_, writeCnxn), message, profileData)), _, _, _)) => {
          println(
            (
              "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
              + "\n Forwarding IntroductionNotification"
              + "\nsessionId = " + sessionId
              + "\ninvoking comet actor with comet message"
              + "\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"))
          CometActorMapper.cometMessage(sessionURIStr, compact(render(
            ("msgType" -> "introductionNotification") ~
              ("content" ->
                ("introSessionId" -> sessionId) ~
                ("correlationId" -> correlationId) ~
                ("connection" ->
                  ("source" -> writeCnxn.src.toString) ~
                  ("label" -> writeCnxn.label) ~
                  ("target" -> writeCnxn.trgt.toString)) ~
                  ("message" -> message.getOrElse("")) ~
                  ("introProfile" -> profileData)))))
        }
        case _ => {
          throw new Exception("Unrecognized response: " + v)
        }
      }
    }

    // BUGBUG : lgm -- need to cancel the subscription when the user
    // refreshes the page. Can we just cancel all subscriptions to the
    // IntroductionNofication on this cnxn at the top of the method
    // and then reissue one for this session?
    cancel(
      introNotificationLabel,
      List(aliasCnxn),
      (optRsrc: Option[mTT.Resource]) => {
        println(
          s"""[listenIntroductionNotification | onCancel \n| cnxn : ${aliasCnxn}; \n| optRsrc = ...]""")
        BasicLogService.tweet("listenIntroductionNotification | onCancel : optRsrc = " + optRsrc)
      })
    feed(
      introNotificationLabel,
      List(aliasCnxn),
      (optRsrc: Option[mTT.Resource]) => {
        println(
          s"""[listenIntroductionNotification | onFeed \n| cnxn : ${aliasCnxn}; \n| optRsrc = ...]""")
        BasicLogService.tweet("listenIntroductionNotification | onFeed : optRsrc = " + optRsrc)
        optRsrc match {
          case None => ();
          // colocated
          case Some(mTT.Ground(Bottom)) => ();
          // distributed
          case Some(mTT.RBoundHM(Some(mTT.Ground(Bottom)), _)) => ();
          // colocated 
          case Some(mTT.Ground(v)) => {
            handleRsp(v)
          }
          // distributed
          case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
            handleRsp(v)
          }
          case _ => {
            println(
              (
                "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
                + "\nIntroductionNotification -- error : unrecognized resource"
                + "\noptRsrc = " + optRsrc
                + "\n~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"))
            throw new Exception("Unrecognized resource: optRsrc = " + optRsrc)
          }
        }
      })
  }

  // TODO: Replace function below with behavior
  def listenConnectNotification(sessionURIStr: String, aliasCnxn: PortableAgentCnxn): Unit = {
    import com.biosimilarity.evaluator.distribution.bfactory.BFactoryDefaultServiceContext._
    import com.biosimilarity.evaluator.distribution.bfactory.BFactoryDefaultServiceContext.eServe._
    import com.protegra_ati.agentservices.protocols.msgs.ConnectNotification

    val connectNotificationLabel = ConnectNotification.toLabel()

    def handleRsp(v: ConcreteHL.HLExpr): Unit = {
      // Launching introduction behavior
      v match {
        case PostedExpr((PostedExpr(ConnectNotification(sessionId, PortableAgentBiCnxn(readCnxn, writeCnxn), profileData)), _, _, _)) => {
          commenceInstance(
            introductionRecipientCnxn,
            introductionRecipientLabel,
            List(readCnxn, aliasCnxn),
            Nil,
            {
              //optRsrc => println( "onCommencement six | " + optRsrc )
              optRsrc => BasicLogService.tweet("onCommencement six | " + optRsrc)
            })
          VerificationBehaviors().launchVerificationAndRelyingPartyBehaviors(
            aliasCnxn.src,
            writeCnxn.trgt,
            //agentMgr().feed _
            feed _)
          CometActorMapper.cometMessage(sessionURIStr, compact(render(
            ("msgType" -> "connectNotification") ~
              ("content" ->
                ("connection" ->
                  ("source" -> writeCnxn.src.toString) ~
                  ("label" -> writeCnxn.label) ~
                  ("target" -> writeCnxn.trgt.toString)) ~
                  ("introProfile" -> profileData)))))
        }
        case _ => {
          throw new Exception("Unrecognized respose: " + v)
        }
      }
    }

    feed(
      connectNotificationLabel,
      List(aliasCnxn),
      (optRsrc: Option[mTT.Resource]) => {
        BasicLogService.tweet("listenConnectNotification | onFeed : optRsrc = " + optRsrc)
        optRsrc match {
          case None => ();
          // colocated
          case Some(mTT.Ground(Bottom)) => ();
          // distributed
          case Some(mTT.RBoundHM(Some(mTT.Ground(Bottom)), _)) => ();
          // either colocated or distributed
          case Some(mTT.Ground(v)) => {
            handleRsp(v)
          }
          case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
            handleRsp(v)
          }
          case _ => {
            throw new Exception("Unrecognized resource: optRsrc = " + optRsrc)
          }
        }
      })
  }

  def onAgentCreation(
    cap: String,
    aliasCnxn: PortableAgentCnxn,
    onSuccess: Unit => Unit = Unit => ()): Unit = {

    import com.biosimilarity.evaluator.distribution.bfactory.BFactoryDefaultServiceContext._
    import com.biosimilarity.evaluator.distribution.bfactory.BFactoryDefaultServiceContext.eServe._

    val aliasURI = new URI("alias://" + cap + "/alias")
    val nodeAgentCap = emailToCap(NodeUser.email)
    val nodeAliasURI = new URI("alias://" + nodeAgentCap + "/alias")
    val nodeUserAliasCnxn = PortableAgentCnxn(nodeAliasURI, "alias", nodeAliasURI)
    val cnxnLabel = UUID.randomUUID().toString
    val nodeToThisCnxn = PortableAgentCnxn(nodeAliasURI, cnxnLabel, aliasURI)
    val thisToNodeCnxn = PortableAgentCnxn(aliasURI, cnxnLabel, nodeAliasURI)
    val biCnxn = PortableAgentBiCnxn(nodeToThisCnxn, thisToNodeCnxn)
    val nodeAgentBiCnxn = PortableAgentBiCnxn(thisToNodeCnxn, nodeToThisCnxn)

    post(
      biCnxnsListLabel,
      List(aliasCnxn),
      Serializer.serialize(List(biCnxn)),
      (optRsrc: Option[mTT.Resource]) => {
        BasicLogService.tweet("connectToNodeUser | onPost : optRsrc = " + optRsrc)
        optRsrc match {
          case None => ()
          case Some(_) => {
            get(
              biCnxnsListLabel,
              List(nodeUserAliasCnxn),
              (optRsrc: Option[mTT.Resource]) => {
                BasicLogService.tweet("connectToNodeUser | onGet : optRsrc = " + optRsrc)
                def handleRsp(v: ConcreteHL.HLExpr): Unit = {
                  val newBiCnxnList = v match {
                    case PostedExpr((PostedExpr(previousBiCnxnListStr: String), _, _, _)) => {
                      nodeAgentBiCnxn :: Serializer.deserialize[List[PortableAgentBiCnxn]](previousBiCnxnListStr)
                    }
                    case Bottom => List(nodeAgentBiCnxn)
                  }
                  put(
                    biCnxnsListLabel,
                    List(nodeUserAliasCnxn),
                    Serializer.serialize(newBiCnxnList),
                    (optRsrc: Option[mTT.Resource]) => {
                      BasicLogService.tweet("connectToNodeUser | onPut : optRsrc = " + optRsrc)
                      optRsrc match {
                        case None => ()
                        case Some(_) => {
                          onSuccess()
                        }
                      }
                    })
                }
                optRsrc match {
                  case None => ();
                  case Some(mTT.Ground(v)) => {
                    handleRsp(v)
                  }
                  case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                    handleRsp(v)
                  }
                  case _ => {
                    throw new Exception("Unrecognized resource: optRsrc = " + optRsrc)
                  }
                }
              })
          }
        }
      })

    // Launching introduction behaviors
    commenceInstance(
      introductionInitiatorCnxn,
      introductionInitiatorLabel,
      List(aliasCnxn),
      Nil,
      {
        //optRsrc => println( "onCommencement one | " + optRsrc )
        optRsrc => BasicLogService.tweet("onCommencement one | " + optRsrc)
      })
    commenceInstance(
      introductionRecipientCnxn,
      introductionRecipientLabel,
      List(nodeToThisCnxn, aliasCnxn),
      Nil,
      {
        //optRsrc => println( "onCommencement two | " + optRsrc )
        optRsrc => BasicLogService.tweet("onCommencement two | " + optRsrc)
      })
    commenceInstance(
      introductionRecipientCnxn,
      introductionRecipientLabel,
      List(thisToNodeCnxn, nodeUserAliasCnxn),
      Nil,
      {
        //optRsrc => println( "onCommencement three | " + optRsrc )
        optRsrc => BasicLogService.tweet("onCommencement three | " + optRsrc)
      })
    //println("onAgentCreation: about to launch claimant behavior")
    BasicLogService.tweet("onAgentCreation: about to launch claimant behavior")
    VerificationBehaviors().launchClaimantBehavior(
      aliasURI,
      feed _)
    VerificationBehaviors().launchVerificationAndRelyingPartyBehaviors(
      aliasURI,
      nodeAliasURI,
      feed _)
    VerificationBehaviors().launchVerificationAndRelyingPartyBehaviors(
      nodeAliasURI,
      aliasURI,
      feed _)
  }

  def createUserRequest(json: JValue, key: String): Unit = {
    import DSLCommLink.mTT
    val email = (json \ "content" \ "email").extract[String].toLowerCase

    if (email == "") {
      // No email, sign up immediately with a random cap
      secureSignup(
        "",
        (json \ "content" \ "password").extract[String],
        compact(render(json \ "content" \ "jsonBlob")),
        key,
        //false
        None)
    } else {
      // Email provided; send a confirmation email
      val token = UUID.randomUUID.toString.substring(0, 8)
      val tokenUri = new URI("token://" + token)
      val tokenCnxn = PortableAgentCnxn(tokenUri, "token", tokenUri)

      val cap = emailToCap(email)
      val capURI = new URI("agent://" + cap)
      val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)

      //val (erql, erspl) = agentMgr().makePolarizedPair()
      // See if the email is already there
      //agentMgr().read(
      def handleRsp(): Unit = {
        // No such email exists, create it
        //val (erql, erspl) = agentMgr().makePolarizedPair()
        post[String](
          tokenLabel,
          List(tokenCnxn),
          // email, password, and jsonBlob
          // TODO: defer asking for password until after confirmaing email
          compact(render(json \ "content")),
          (optRsrc: Option[mTT.Resource]) => {
            BasicLogService.tweet("createUserRequest | onPost: optRsrc = " + optRsrc)
            optRsrc match {
              case None => ();
              case Some(_) => {
                ConfirmationEmail.confirm(email, token)
                // Notify user to check her email
                CompletionMapper.complete(key, compact(render(
                  ("msgType" -> "createUserWaiting") ~
                    ("content" -> List()) // List() is rendered as "{}" 
                    )))
              }
            }
          })
      }

      read(
        jsonBlobLabel,
        List(capSelfCnxn),
        (optRsrc: Option[mTT.Resource]) => {
          BasicLogService.tweet("createUserRequest | email case | anonymous onFetch: optRsrc = " + optRsrc)
          optRsrc match {
            case None => ();
            case Some(mTT.Ground(Bottom)) => {
              handleRsp()
            }
            case Some(mTT.RBoundHM(Some(mTT.Ground(Bottom)), _)) => {
              handleRsp()
            }
            case _ => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "createUserError") ~
                  ("content" ->
                    ("reason" -> "Email is already registered.")))))
            }
          }
        })
    }
  }

  def secureLogin(
    identType: String,
    identInfo: String,
    password: String,
    key: String): Unit = {
    import DSLCommLink.mTT

    val sessionToken = generateRandomHex()

    def login(cap: String): Unit = {
      val capURI = new URI("agent://" + cap)
      val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)
      val sessionURI = "agent-session://" + cap + "/" + sessionToken

      val onPwmacFetch: Option[mTT.Resource] => Unit = (rsrc) => {
        //println("secureLogin | login | onPwmacFetch: rsrc = " + rsrc)
        BasicLogService.tweet("secureLogin | login | onPwmacFetch: rsrc = " + rsrc)
        def handlePWMACRsp(pwmac: String): Unit = {
          BasicLogService.tweet("secureLogin | login | onPwmacFetch: pwmac = " + pwmac)
          val macInstance = Mac.getInstance("HmacSHA256")
          macInstance.init(new SecretKeySpec("pAss#4$#".getBytes("utf-8"), "HmacSHA256"))
          val hex = macInstance.doFinal(password.getBytes("utf-8")).map("%02x" format _).mkString
          BasicLogService.tweet("secureLogin | login | onPwmacFetch: hex = " + hex)
          if (hex != pwmac.toString) {
            BasicLogService.tweet("secureLogin | login | onPwmacFetch: Password mismatch.")
            CompletionMapper.complete(key, compact(render(
              ("msgType" -> "initializeSessionError") ~
                ("content" -> ("reason" -> "Bad password.")))))
          } else {
            def biCnxnToJObject(biCnxn: PortableAgentBiCnxn): JObject = {
              ("source" -> biCnxn.writeCnxn.src.toString) ~
                ("label" -> biCnxn.writeCnxn.label) ~
                ("target" -> biCnxn.writeCnxn.trgt.toString)
            }
            def onLabelsFetch(jsonBlob: String, aliasList: String, defaultAlias: String, biCnxnList: String): Option[mTT.Resource] => Unit = (optRsrc) => {
              BasicLogService.tweet("secureLogin | login | onPwmacFetch | onLabelsFetch: optRsrc = " + optRsrc)
              def handleRsp(v: ConcreteHL.HLExpr): Unit = {
                v match {
                  case PostedExpr((PostedExpr(labelList: String), _, _, _)) => {
                    // TODO: Replace notification block below with behavior code
                    val aliasCnxn = PortableAgentCnxn(capURI, defaultAlias, capURI)
                    listenIntroductionNotification(sessionURI, aliasCnxn)
                    listenConnectNotification(sessionURI, aliasCnxn)

                    // Fetch the encrypted wallet, decrypt it with the password and
                    // store it in memory under the sessionToken
                    //                     fetch(
                    //                       btcWIFKeyLongTermStorage,
                    //                       List( aliasCnxn ),
                    //                       ( optRsrc : Option[mTT.Resource] ) => {
                    // Unpack encrypted key from resource
                    //                         def handleRsp( v : ConcreteHL.HLExpr ) : Unit = {
                    //                           v match {
                    // BUGBUG : lgm -- there's a race
                    //                             case Bottom => {
                    //                               CompletionMapper.complete(key, compact(render(
                    //                                 ("msgType" -> "initializeSessionError") ~
                    //                                 ("content" -> ("reason" -> "Failed to load BTC WIF key.")) 
                    //                               )))
                    //                             }
                    //                             case PostedExpr( (PostedExpr( encryptedKey : String ), _, _, _) ) => {
                    // BUGBUG : lgm -- fix the padding and
                    // uncomment these lines
                    //val btcWIFKey = decryptWithSaltedPassword(password, hexStringToByteArray(encryptedKey)).map(_.toChar).mkString
                    //btcKeyMapper.map += ((sessionToken, btcWIFKey))
                    /* println("onLabelsFetch / fetch btc wifkey: Added " + */ /*(sessionToken,btcWIFKey) + */ /* "to in-memory map") */
                    //                             }
                    //                           }
                    //                         }
                    //                         optRsrc match {
                    //                           case None => ();
                    //                           case Some(mTT.Ground(v)) => {
                    //                             handleRsp( v ) 
                    //                           }
                    //                           case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                    //                             handleRsp( v ) 
                    //                           }
                    //                           case _ => {
                    //                             CompletionMapper.complete(key, compact(render(
                    //                               ("msgType" -> "initializeSessionError") ~
                    //                               ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc)))
                    //                             )))
                    //                           }
                    //                         }
                    //                       }
                    //                     )

                    val biCnxnListObj = Serializer.deserialize[List[PortableAgentBiCnxn]](biCnxnList)

                    val content: JsonAST.JObject =
                      ("sessionURI" -> sessionURI) ~
                        ("listOfAliases" -> parse(aliasList)) ~
                        ("defaultAlias" -> defaultAlias) ~
                        ("listOfLabels" -> parse(labelList)) ~ // for default alias
                        ("listOfConnections" -> biCnxnListObj.map(biCnxnToJObject(_))) ~ // for default alias
                        ("lastActiveLabel" -> "") ~
                        ("jsonBlob" -> parse(jsonBlob))

                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionResponse") ~
                        ("content" -> content))))
                  }
                  case Bottom => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> "Strange: found other data but not labels!?")))))
                  }
                }
              }
              optRsrc match {
                case None => ();
                case Some(mTT.Ground(v)) => {
                  handleRsp(v)
                }
                case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                  handleRsp(v)
                }
                case _ => {
                  CompletionMapper.complete(key, compact(render(
                    ("msgType" -> "initializeSessionError") ~
                      ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                }
              }
            }
            def onConnectionsFetch(jsonBlob: String, aliasList: String, defaultAlias: String): Option[mTT.Resource] => Unit = (optRsrc) => {
              BasicLogService.tweet("secureLogin | login | onPwmacFetch | onConnectionsFetch: optRsrc = " + optRsrc)
              val aliasCnxn = PortableAgentCnxn(capURI, defaultAlias, capURI)
              def handleRsp(v: ConcreteHL.HLExpr): Unit = {
                v match {
                  case PostedExpr((PostedExpr(biCnxnList: String), _, _, _)) => {
                    val biCnxnListObj = Serializer.deserialize[List[PortableAgentBiCnxn]](biCnxnList)
                    // Get the profile of each target in the list
                    biCnxnListObj.map((biCnxn: PortableAgentBiCnxn) => {
                      // Construct self-connection for each target
                      val targetURI = biCnxn match {
                        case PortableAgentBiCnxn(read, _) => read.src
                      }
                      val targetSelfCnxn = PortableAgentCnxn(targetURI, "identity", targetURI)
                      def handleFetchRsp(
                        optRsrc: Option[mTT.Resource],
                        v: ConcreteHL.HLExpr): Unit = {
                        v match {
                          case PostedExpr((PostedExpr(jsonBlob: String), _, _, _)) => {
                            CometActorMapper.cometMessage(sessionURI, compact(render(
                              ("msgType" -> "connectionProfileResponse") ~
                                ("content" -> (
                                  ("sessionURI" -> sessionURI) ~
                                  ("connection" -> biCnxnToJObject(biCnxn)) ~
                                  ("jsonBlob" -> jsonBlob))))))
                          }
                          case Bottom => {
                            CometActorMapper.cometMessage(sessionURI, compact(render(
                              ("msgType" -> "connectionProfileError") ~
                                ("content" -> (
                                  ("sessionURI" -> sessionURI) ~
                                  ("connection" -> biCnxnToJObject(biCnxn)) ~
                                  ("reason" -> "Not found"))))))
                          }
                          case _ => {
                            CompletionMapper.complete(key, compact(render(
                              ("msgType" -> "initializeSessionError") ~
                                ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                          }
                        }
                      }

                      fetch(
                        jsonBlobLabel,
                        List(targetSelfCnxn),
                        (optRsrc: Option[mTT.Resource]) => {
                          optRsrc match {
                            case None => ();
                            case Some(mTT.Ground(v)) => {
                              handleFetchRsp(optRsrc, v)
                            }
                            case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                              handleFetchRsp(optRsrc, v)
                            }
                            case _ => {
                              CompletionMapper.complete(key, compact(render(
                                ("msgType" -> "initializeSessionError") ~
                                  ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                            }
                          }
                        })
                    })
                    fetch(labelListLabel, List(aliasCnxn), onLabelsFetch(jsonBlob, aliasList, defaultAlias, biCnxnList))
                  }
                  case Bottom => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> "Strange: found other data but not connections!?")))))
                  }
                  case _ => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                  }
                }
              }
              optRsrc match {
                case None => ();
                case Some(mTT.Ground(v)) => {
                  handleRsp(v)
                }
                case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                  handleRsp(v)
                }
                case _ => {
                  CompletionMapper.complete(key, compact(render(
                    ("msgType" -> "initializeSessionError") ~
                      ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                }
              }
            }
            def onDefaultAliasFetch(jsonBlob: String, aliasList: String): Option[mTT.Resource] => Unit = (optRsrc) => {
              BasicLogService.tweet("secureLogin | login | onPwmacFetch | onDefaultAliasFetch: optRsrc = " + optRsrc)
              def handleRsp(optRsrc: Option[mTT.Resource], v: ConcreteHL.HLExpr): Unit = {
                v match {
                  case PostedExpr((PostedExpr(defaultAlias: String), _, _, _)) => {
                    val aliasCnxn = PortableAgentCnxn(capURI, defaultAlias, capURI)
                    fetch(biCnxnsListLabel, List(aliasCnxn), onConnectionsFetch(jsonBlob, aliasList, defaultAlias))
                  }
                  case Bottom => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> "Strange: found other data but not default alias!?")))))
                  }
                  case _ => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                  }
                }
              }
              optRsrc match {
                case None => ();
                case Some(mTT.Ground(v)) => {
                  handleRsp(optRsrc, v)
                }
                case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                  handleRsp(optRsrc, v)
                }
                case _ => {
                  CompletionMapper.complete(key, compact(render(
                    ("msgType" -> "initializeSessionError") ~
                      ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                }
              }
            }
            def onAliasesFetch(jsonBlob: String): Option[mTT.Resource] => Unit = (optRsrc) => {
              BasicLogService.tweet("secureLogin | login | onPwmacFetch | onAliasesFetch: optRsrc = " + optRsrc)
              def handleRsp(optRsrc: Option[mTT.Resource], v: ConcreteHL.HLExpr): Unit = {
                v match {
                  case PostedExpr((PostedExpr(aliasList: String), _, _, _)) => {
                    //agentMgr().fetch(defaultAliasLabel,
                    //List(capSelfCnxn),
                    //onDefaultAliasFetch(jsonBlob, aliasList))
                    fetch(defaultAliasLabel, List(capSelfCnxn), onDefaultAliasFetch(jsonBlob, aliasList))
                  }
                  case Bottom => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> "Strange: found pwmac and jsonBlob but not aliases!?")))))
                  }
                  case _ => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                  }
                }
              }
              optRsrc match {
                case None => ();
                case Some(mTT.Ground(v)) => {
                  handleRsp(optRsrc, v)
                }
                case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                  handleRsp(optRsrc, v)
                }
                case _ => {
                  CompletionMapper.complete(key, compact(render(
                    ("msgType" -> "initializeSessionError") ~
                      ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                }
              }
            }
            val onJSONBlobFetch: Option[mTT.Resource] => Unit = (optRsrc) => {
              BasicLogService.tweet("secureLogin | login | onPwmacFetch | onJSONBlobFetch: optRsrc = " + optRsrc)
              def handleRsp(optRsrc: Option[mTT.Resource], v: ConcreteHL.HLExpr): Unit = {
                v match {
                  case PostedExpr((PostedExpr(jsonBlob: String), _, _, _)) => {
                    //val (erql, erspl) = agentMgr().makePolarizedPair()
                    //agentMgr().fetch( erql, erspl )(aliasListLabel, List(capSelfCnxn), onAliasesFetch(jsonBlob))
                    fetch(aliasListLabel, List(capSelfCnxn), onAliasesFetch(jsonBlob))
                  }
                  case Bottom => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> "Strange: found pwmac but not jsonBlob!?")))))
                  }
                  case _ => {
                    CompletionMapper.complete(key, compact(render(
                      ("msgType" -> "initializeSessionError") ~
                        ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                  }
                }
              }
              optRsrc match {
                case None => ();
                case Some(mTT.Ground(v)) => {
                  handleRsp(optRsrc, v)
                }
                case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                  handleRsp(optRsrc, v)
                }
                case _ => {
                  CompletionMapper.complete(key, compact(render(
                    ("msgType" -> "initializeSessionError") ~
                      ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
                }
              }
            }
            //val (erql, erspl) = agentMgr().makePolarizedPair()
            //agentMgr().fetch( erql, erspl )(jsonBlobLabel, List(capSelfCnxn), onJSONBlobFetch)
            fetch(jsonBlobLabel, List(capSelfCnxn), onJSONBlobFetch)
            ()
          }
        }

        rsrc match {
          // At this point the cap is good, but we have to verify the pw mac
          case None => ();
          case Some(mTT.Ground(PostedExpr((PostedExpr(pwmac: String), _, _, _)))) => {
            handlePWMACRsp(pwmac)
          }
          case Some(mTT.RBoundHM(Some(mTT.Ground(PostedExpr((PostedExpr(pwmac: String), _, _, _)))), _)) => {
            handlePWMACRsp(pwmac)
          }
          case _ => {
            CompletionMapper.complete(key, compact(render(
              ("msgType" -> "initializeSessionError") ~
                ("content" -> ("reason" -> ("Unrecognized resource: rsrc = " + rsrc))))))
          }
        }
      }
      //val (erql, erspl) = agentMgr().makePolarizedPair()
      //BasicLogService.tweet("secureLogin | login: fetching with eqrl, erspl = " + erql + ", " + erspl)
      //agentMgr().fetch( erql, erspl )(pwmacLabel, List(capSelfCnxn), onPwmacFetch)
      fetch(pwmacLabel, List(capSelfCnxn), onPwmacFetch)
    }

    // identType is either "cap" or "email"
    identType match {
      case "cap" => {
        BasicLogService.tweet("secureLogin | cap branch")
        val cap = identInfo.slice(0, 36)
        val mac = identInfo.slice(36, 46)
        val macInstance = Mac.getInstance("HmacSHA256")
        macInstance.init(new SecretKeySpec("5ePeN42X".getBytes("utf-8"), "HmacSHA256"))
        val hex = macInstance.doFinal(cap.getBytes("utf-8")).slice(0, 5).map("%02x" format _).mkString
        if (hex != mac) {
          CompletionMapper.complete(key, compact(render(
            ("msgType" -> "initializeSessionError") ~
              ("content" -> ("reason" -> "This link wasn't generated by us.")))))
        } else {
          BasicLogService.tweet("Link OK, logging in")
          login(cap)
        }
      }

      case "email" => {
        val email = identInfo.toLowerCase
        BasicLogService.tweet("secureLogin | email branch: email = " + email)
        // hash the email to get cap
        val cap = emailToCap(email)
        // don't need mac of cap; need to verify email is on our network
        val emailURI = new URI("emailhash://" + cap)
        val emailSelfCnxn = PortableAgentCnxn(emailURI, "emailhash", emailURI)
        //val (erql, erspl) = agentMgr().makePolarizedPair()
        //BasicLogService.tweet("secureSignup | email branch: erql, erspl = " + erql + ", " + erspl)        
        def handleRsp(optRsrc: Option[mTT.Resource], v: ConcreteHL.HLExpr): Unit = {
          v match {
            case Bottom => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "initializeSessionError") ~
                  ("content" ->
                    ("reason" -> "No such email.")))))
            }
            case PostedExpr((PostedExpr(cap: String), _, _, _)) => {
              BasicLogService.tweet("secureLogin | Logging in with cap = " + cap);
              login(cap)
            }
            case _ => {
              CompletionMapper.complete(key, compact(render(
                ("msgType" -> "initializeSessionError") ~
                  ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
            }
          }
        }

        read(
          emailLabel,
          List(emailSelfCnxn),
          (optRsrc: Option[mTT.Resource]) => {
            BasicLogService.tweet("secureLogin | email case | anonymous onFetch: optRsrc = " + optRsrc)
            optRsrc match {
              case None => ();
              case Some(mTT.Ground(v)) => {
                handleRsp(optRsrc, v)
              }
              case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                handleRsp(optRsrc, v)
              }
              case _ => {
                CompletionMapper.complete(key, compact(render(
                  ("msgType" -> "initializeSessionError") ~
                    ("content" -> ("reason" -> ("Unrecognized resource: optRsrc = " + optRsrc))))))
              }
            }
          })
      }
    }
  }

  def initializeSessionRequest(
    json: JValue,
    key: String): Unit = {
    val agentURI = (json \ "content" \ "agentURI").extract[String]
    val uri = new URI(agentURI)

    if (uri.getScheme() != "agent") {
      throw InitializeSessionException(agentURI, "Unrecognized scheme")
    }
    val identType = uri.getHost()
    val identInfo = uri.getPath.substring(1) // drop leading slash
    // TODO: get a proper library to do this
    val queryMap = new HashMap[String, String]
    uri.getRawQuery.split("&").map((x: String) => {
      val pair = x.split("=")
      queryMap += ((pair(0), pair(1)))
    })
    var password = queryMap.get("password").getOrElse("")
    secureLogin(identType, identInfo, password, key)
  }

  def extractCnxn(cx: JObject) = new PortableAgentCnxn(
    new URI((cx \ "source").extract[String]),
    (cx \ "label").extract[String],
    new URI((cx \ "target").extract[String]))

  def updateUserRequest(json: JValue): Unit = {
    val content = (json \ "content").asInstanceOf[JObject]
    val sessionURIStr = (content \ "sessionURI").extract[String]
    val sessionURI = new URI(sessionURIStr)
    val agentURIStr = sessionURIStr.replaceFirst("agent-session", "agent")
    val agentURI = new URI(agentURIStr)
    val agentIdCnxn = PortableAgentCnxn(agentURI, "identity", agentURI)
    //val (erql, erspl) = agentMgr().makePolarizedPair()
    //agentMgr().get(erql, erspl)(
    get(
      jsonBlobLabel,
      List(agentIdCnxn),
      (optRsrc: Option[mTT.Resource]) => {
        //println( "updateUserRequest | onGet | optRsrc: " + optRsrc )
        BasicLogService.tweet("updateUserRequest | onGet | optRsrc: " + optRsrc)
        def handlePostedStr(postedStr: String): Unit = {
          put(
            jsonBlobLabel,
            List(agentIdCnxn),
            compact(render(json \ "content" \ "jsonBlob")),
            (optRsrc: Option[mTT.Resource]) => {
              optRsrc match {
                case None => ()
                case Some(_) => {
                  CometActorMapper.cometMessage(sessionURIStr, compact(render(
                    ("msgType" -> "updateUserResponse") ~
                      ("content" -> ("sessionURI" -> sessionURIStr)))))
                }
              }
            })
        }
        optRsrc match {
          case None => ();
          case Some(mTT.Ground(PostedExpr((PostedExpr(postedStr: String), _, _, _)))) => {
            handlePostedStr(postedStr)
          }
          case Some(mTT.RBoundHM(Some(mTT.Ground(PostedExpr((PostedExpr(postedStr: String), _, _, _)))), _)) => {
            handlePostedStr(postedStr)
          }
          case _ => {
            CometActorMapper.cometMessage(sessionURIStr, compact(render(
              ("msgType" -> "updateUserError") ~
                ("content" -> ("reason" -> ("Unrecognized resource: " + optRsrc.toString))))))
          }
        }
      })
  }

  import scala.util.parsing.combinator._
  type Path = List[String]
  class SumOfProducts extends RegexParsers {

    def Node: Parser[String] = """[A-Za-z0-9]+""".r

    def Empty: Parser[Set[List[Path]]] = """^$""".r ^^ { (s: String) => Set[List[Path]]() }

    def Path: Parser[Set[List[Path]]] = "[" ~> repsep(Node, ",") <~ "]" ^^
      {
        // A path is a trivial sum of a trivial product
        (nodes: List[String]) =>
          Set(List(nodes.reverse))
      }

    def Sum: Parser[Set[List[Path]]] = ("each" | "any") ~ "(" ~> repsep(SOP, ",") <~ ")" ^^
      {
        // Given a list of sums of products, return the sum of the list
        (sops: List[Set[List[Path]]]) =>
          (Set[List[Path]]() /: sops)(_ union _)
      }

    def Product: Parser[Set[List[Path]]] = "all(" ~> repsep(SOP, ",") <~ ")" ^^
      {
        (sops: List[Set[List[Path]]]) =>
          sops match {
            case Nil => Set(List(List[String]()))
            // case sop::Nil => sop
            case sop :: tail => {
              val zero = Set(List(List[String]()))
              sops.foldLeft(zero)((acc, sop2) => {
                if (acc == zero) sop2
                else for (prod <- acc; prod2 <- sop2) yield {
                  (prod ++ prod2).sortWith((a, b) => a.mkString < b.mkString)
                }
              })
            }
          }
      }

    def SOP: Parser[Set[List[Path]]] = Empty | Path | Product | Sum

    def sumOfProductsToFilterSet(sop: Set[List[Path]]): Set[CnxnCtxtLabel[String, String, String] with Factual] = {
      val filterSet = for (prod <- sop) yield {
        // List(List("Greg", "Biosim", "Work"), List("Personal"))
        // => fromTermString("all(vWork(vBiosim(vGreg(_))), vPersonal(_))").get
        fromTermString("all(" + prod.map(path => {
          val (l, r) = path.foldLeft(("", ""))((acc, tag) => {
            val (l2, r2) = acc
            (l2 + "v" + tag + "(", ")" + r2)
          })
          l + "VAR" + UUID.randomUUID.toString.substring(0, 8) + r
        }).mkString(",") + ")").get.asInstanceOf[CnxnCtxtLabel[String, String, String] with Factual]
      }
      filterSet.isEmpty match {
        // Default to the "match everything" filter
        case true => sumOfProductsToFilterSet(Set(List(List())))
        case false => filterSet
      }
    }

    def apply(s: String) = sumOfProductsToFilterSet(parseAll(SOP, s).get)
  }

  def extractFiltersAndCnxns(exprContent: JObject): Option[(Set[CnxnCtxtLabel[String, String, String] with Factual], List[PortableAgentCnxn])] = {
    BasicLogService.tweet("Extracting from " + compact(render(exprContent)))
    try {
      val labelSet = new SumOfProducts()((exprContent \ "label").extract[String])
      val cnxns = (exprContent \ "cnxns") match {
        case JArray(arr: List[JObject]) => arr.map(extractCnxn _)
      }
      Some((labelSet, cnxns))
    } catch {
      case _: Throwable => None
    }
  }

  def extractMetadata(ccl: CnxnCtxtLabel[String, String, String]): (CnxnCtxtLabel[String, String, String] with Factual, String, Either[String, String], String) =
    {
      def cclToPath(ccl: CnxnCtxtLabel[String, String, String]): List[String] = {
        ccl match {
          case CnxnCtxtBranch(tag, List(CnxnCtxtLeaf(Right(_)))) => List(tag.substring(1))
          case CnxnCtxtBranch(tag, children) => tag.substring(1) :: cclToPath(children(0))
          case CnxnCtxtLeaf(Right(_)) => List()
        }
      }
      // Assume ccl is of the form user(p1(all(...)), p2(uid(...)), p3(new(_)|old(_)), p4(nil(_)))
      ccl match {
        case CnxnCtxtBranch("user",
          CnxnCtxtBranch("p1", filter :: Nil) ::
            CnxnCtxtBranch("p2", uid :: Nil) ::
            CnxnCtxtBranch("p3", age :: Nil) :: _
          ) => (
          filter,
          filter match {
            case CnxnCtxtBranch("all", factuals) => {
              "all(" + factuals.map("[" + cclToPath(_).reverse.mkString(",") + "]").mkString(",") + ")"
            }
          },
          uid match {
            case CnxnCtxtBranch("uid", factuals) => factuals(0) match {
              case CnxnCtxtLeaf(tag: Either[String, String]) => tag
            }
          },
          age match {
            case CnxnCtxtBranch(ageStr: String, _) => ageStr
          })
      }
    }

  def subst(
    ccl: CnxnCtxtLabel[String, String, String] with Factual,
    bindings: Map[String, CnxnCtxtLabel[String, String, String] with Factual]): CnxnCtxtLabel[String, String, String] with Factual = {
    import com.biosimilarity.lift.lib.term.conversion._
    import com.biosimilarity.lift.model.store._
    import com.biosimilarity.lift.lib._
    object CCLSubst extends CnxnSubstitution[String, String, String] with CnxnString[String, String, String]
    CCLSubst.substitute(ccl)(bindings)
  }

  def toTermString(ccl: CnxnCtxtLabel[String, String, String] with Factual): String = {
    ccl match {
      case CnxnCtxtBranch(functor, factuals) => {
        functor + "(" + factuals.map(toTermString).mkString(",") + ")"
      }
      case CnxnCtxtLeaf(Left(s)) => compact(render(JString(s)))
      case CnxnCtxtLeaf(Right(s)) => s
    }
  }

  def evalSubscribeRequest(json: JValue): Unit = {
    import com.biosimilarity.evaluator.distribution.portable.v0_1._
    import com.protegra_ati.agentservices.store._
    import com.biosimilarity.evaluator.prolog.PrologDSL._

    object act extends AgentCnxnTypes {}

    BasicLogService.tweet("evalSubscribeRequest: json = " + compact(render(json)));
    val content = (json \ "content").asInstanceOf[JObject]
    val sessionURIStr = (content \ "sessionURI").extract[String]

    val expression = (content \ "expression")
    val ec = (expression \ "content").asInstanceOf[JObject]
    val optFiltersAndCnxns = extractFiltersAndCnxns(ec)
    if (optFiltersAndCnxns == None) {
      CometActorMapper.cometMessage(
        sessionURIStr,
        """{"msgType":"evalSubscribeError","content":{"reason":"Invalid label."}}""")
    } else {
      val (filters, cnxns) = optFiltersAndCnxns.get
      val exprType = (expression \ "msgType").extract[String]
      exprType match {
        case "feedExpr" => {
          BasicLogService.tweet("evalSubscribeRequest | feedExpr")
          val onFeed: Option[mTT.Resource] => Unit = (optRsrc) => {
            BasicLogService.tweet("evalSubscribeRequest | onFeed: rsrc = " + optRsrc)
            //println("evalSubscribeRequest | onFeed: optRsrc = " + optRsrc)

            def handleTuple(v: ConcreteHL.HLExpr): Unit = {
              v match {
                case PostedExpr(tuple) => {
                  val (postedStr, filter, cnxn, bindings) = tuple match {
                    case (a, b, c, d) => (
                      a match {
                        case PostedExpr(postedStr: String) => postedStr
                        case _ => throw new Exception("Expected PostedExpr(postedStr: String)")
                      },
                      b match {
                        case filter: CnxnCtxtLabel[String, String, String] with Factual => filter
                        case _ => throw new Exception("Expected CnxnCtxtLabel[String,String,String] with Factual")
                      },
                      c,
                      d.asInstanceOf[mTT.RBoundAList])
                    case _ => throw new Exception("Wrong number of elements")
                  }
                  val (cclFilter, jsonFilter, uid, age) = extractMetadata(filter)
                  val agentCnxn = cnxn.asInstanceOf[act.AgentCnxn]
                  //println("evalSubscribeRequest | onFeed | republishing in history; bindings = " + bindings)
                  BasicLogService.tweet("evalSubscribeRequest | onFeed | republishing in history; bindings = " + bindings)
                  val arr = parse(postedStr).asInstanceOf[JArray].arr
                  val json = compact(render(arr(0)))
                  val originalFilter = fromTermString(arr(1).asInstanceOf[JString].s).get.asInstanceOf[CnxnCtxtLabel[String, String, String] with Factual]
                  post(
                    'user(
                      'p1(originalFilter),
                      // TODO(mike): temporary workaround until bindings bug is fixed.
                      'p2('uid((arr(0) \ "uid").extract[String])),
                      'p3('old("_")),
                      'p4('nil("_"))),
                    List(PortableAgentCnxn(agentCnxn.src, agentCnxn.label, agentCnxn.trgt)),
                    postedStr,
                    //(optRsrc) => { println ("evalSubscribeRequest | onFeed | republished: uid = " + uid) }
                    (optRsrc) => { BasicLogService.tweet("evalSubscribeRequest | onFeed | republished: uid = " + uid) })

                  val content =
                    ("sessionURI" -> sessionURIStr) ~
                      ("pageOfPosts" -> List(json)) ~
                      ("connection" -> (
                        ("source" -> agentCnxn.src.toString) ~
                        ("label" -> agentCnxn.label) ~
                        ("target" -> agentCnxn.trgt.toString))) ~
                        ("filter" -> jsonFilter)
                  val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
                  //println("evalSubscribeRequest | onFeed: response = " + compact(render(response)))
                  BasicLogService.tweet("evalSubscribeRequest | onFeed: response = " + compact(render(response)))
                  CometActorMapper.cometMessage(sessionURIStr, compact(render(response)))
                }
              }
            }

            def handleBottom(): Unit = {
              val content =
                ("sessionURI" -> sessionURIStr) ~
                  ("pageOfPosts" -> List[String]())
              val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
              //println("evalSubscribeRequest | onFeed: response = " + compact(render(response)))
              BasicLogService.tweet("evalSubscribeRequest | onFeed: response = " + compact(render(response)))
              CometActorMapper.cometMessage(sessionURIStr, compact(render(response)))
            }

            optRsrc match {
              case None => ();
              case Some(mTT.Ground(Bottom)) => {
                handleBottom()
              }
              case Some(mTT.RBoundHM(Some(mTT.Ground(Bottom)), _)) => {
                handleBottom()
              }
              case Some(mTT.Ground(v)) => {
                handleTuple(v)
              }
              case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                handleTuple(v)
              }
              case _ => throw new Exception("Unrecognized resource: " + optRsrc)
            }
          }
          // TODO(mike): Workaround until bindings bug is fixed
          def handleRsp(v: ConcreteHL.HLExpr): Unit = {
            v match {
              case PostedExpr(
                (PostedExpr(postedStr: String), filter: CnxnCtxtLabel[String, String, String], cnxn, bindings)
                ) => {
                val arr = parse(postedStr).asInstanceOf[JArray].arr
                val json = compact(render(arr(0)))
                val (cclFilter, jsonFilter, uid, age) = extractMetadata(filter)
                val agentCnxn = cnxn.asInstanceOf[act.AgentCnxn]
                val content =
                  ("sessionURI" -> sessionURIStr) ~
                    ("pageOfPosts" -> List(json)) ~
                    ("connection" -> (
                      ("source" -> agentCnxn.src.toString) ~
                      ("label" -> agentCnxn.label) ~
                      ("target" -> agentCnxn.trgt.toString))) ~
                      ("filter" -> jsonFilter)
                val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
                //println("evalSubscribeRequest | onRead: response = " + compact(render(response)))
                BasicLogService.tweet("evalSubscribeRequest | onRead: response = " + compact(render(response)))
                CometActorMapper.cometMessage(sessionURIStr, compact(render(response)))
              }
              case Bottom => {
                val content =
                  ("sessionURI" -> sessionURIStr) ~
                    ("pageOfPosts" -> List[String]())
                val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
                //println("evalSubscribeRequest | onRead: response = " + compact(render(response)))
                BasicLogService.tweet("evalSubscribeRequest | onRead: response = " + compact(render(response)))
                CometActorMapper.cometMessage(sessionURIStr, compact(render(response)))
              }
            }
          }

          val onRead: Option[mTT.Resource] => Unit = (optRsrc) => {
            //println("evalSubscribeRequest | onRead: optRsrc = " + optRsrc)
            BasicLogService.tweet("evalSubscribeRequest | onRead: rsrc = " + optRsrc)
            optRsrc match {
              case None => ();
              // colocated
              case Some(mTT.Ground(v)) => {
                handleRsp(v)
              }
              // either colocated or distributed
              case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                handleRsp(v)
              }
              case _ => throw new Exception("Unrecognized resource: " + optRsrc)
            }
          }

          //println("evalSubscribeRequest | feedExpr: calling feed")
          BasicLogService.tweet("evalSubscribeRequest | feedExpr: calling feed")
          val uid = try {
            'uid((ec \ "uid").extract[String])
          } catch {
            case _: Throwable => 'uid("UID")
          }
          for (filter <- filters) {
            //println("evalSubscribeRequest | feedExpr: filter = " + filter)
            BasicLogService.tweet("evalSubscribeRequest | feedExpr: filter = " + filter)
            feed(
              'user('p1(filter), 'p2(uid), 'p3('new("_")), 'p4('nil("_"))),
              cnxns.map((c) => PortableAgentCnxn(c.trgt, c.label, c.src)),
              onFeed)
            read(
              'user('p1(filter), 'p2(uid), 'p3('old("_")), 'p4('nil("_"))),
              cnxns.map((c) => PortableAgentCnxn(c.trgt, c.label, c.src)),
              onRead)
          }
        }
        case "scoreExpr" => {
          BasicLogService.tweet("evalSubscribeRequest | scoreExpr")
          val onScore: Option[mTT.Resource] => Unit = (optRsrc) => {
            BasicLogService.tweet("evalSubscribeRequest | onScore: optRsrc = " + optRsrc)
            //println("evalSubscribeRequest | onScore: optRsrc = " + optRsrc)
            def handlePostedExpr(v: ConcreteHL.HLExpr): Unit = {
              v match {
                case PostedExpr((PostedExpr(postedStr: String), filter: CnxnCtxtLabel[String, String, String], cnxn, bindings)) => {
                  val (cclFilter, jsonFilter, uid, age) = extractMetadata(filter)
                  val agentCnxn = cnxn.asInstanceOf[act.AgentCnxn]
                  val arr = parse(postedStr).asInstanceOf[JArray].arr
                  val json = compact(render(arr(0)))
                  val content =
                    ("sessionURI" -> sessionURIStr) ~
                      ("pageOfPosts" -> List(json)) ~
                      ("connection" -> (
                        ("source" -> agentCnxn.src.toString) ~
                        ("label" -> agentCnxn.label) ~
                        ("target" -> agentCnxn.trgt.toString))) ~
                        ("filter" -> jsonFilter)
                  val response = ("msgType" -> "evalSubscribeResponse") ~ ("content" -> content)
                  BasicLogService.tweet("evalSubscribeRequest | onScore: response = " + compact(render(response)))
                  CometActorMapper.cometMessage(sessionURIStr, compact(render(response)))
                }
                case _ => {
                  throw new Exception("Unrecognized response: " + v)
                }
              }
            }
            optRsrc match {
              case None => ();
              case Some(mTT.Ground(v)) => {
                handlePostedExpr(v)
              }
              case Some(mTT.RBoundHM(Some(mTT.Ground(v)), _)) => {
                handlePostedExpr(v)
              }
              case _ => throw new Exception("Unrecognized resource: " + optRsrc)
            }
          }
          val staff = (expression \ "content" \ "staff") match {
            case JObject(List((which: String, vals @ JArray(_)))) => {
              // Either[Seq[PortableAgentCnxn],Seq[CnxnCtxtLabel[String,String,String]]]
              which match {
                case "a" => vals match {
                  case JArray(arr: List[JObject]) => Left(arr.map(extractCnxn _))
                }
                case "b" => Right(
                  vals.extract[List[String]].map((t: String) =>
                    fromTermString(t).getOrElse(throw new Exception("Couldn't parse staff: " + json))))
                case _ => throw new Exception("Couldn't parse staff: " + json)
              }
            }
            case _ => throw new Exception("Couldn't parse staff: " + json)
          }
          BasicLogService.tweet("evalSubscribeRequest | feedExpr: calling score")
          val uid = try {
            'uid((ec \ "uid").extract[String])
          } catch {
            case _: Throwable => 'uid("UID")
          }
          for (filter <- filters) {
            //agentMgr().score(
            score(
              'user('p1(filter), 'p2(uid), 'p3('new("_")), 'p4('nil("_"))),
              cnxns.map((c) => PortableAgentCnxn(c.trgt, c.label, c.src)),
              staff,
              onScore)
            // TODO(mike): Make a read version of score, implement history for score.
          }
        }
        case "insertContent" => {
          //println("evalSubscribeRequest | insertContent")
          BasicLogService.tweet("evalSubscribeRequest | insertContent")
          //println("evalSubscribeRequest | insertContent: calling post")
          BasicLogService.tweet("evalSubscribeRequest | insertContent: calling post")
          val value = (ec \ "value").extract[String]
          val uid = 'uid(Ground((ec \ "uid").extract[String]))

          for (filter <- filters) {
            BasicLogService.tweet("evalSubscribeRequest | insertContent: calling post with filter " + filter)
            //agentMgr().post(
            post(
              'user('p1(filter), 'p2(uid), 'p3('new("_")), 'p4('nil("_"))),
              cnxns,
              "[" + value + ", " + compact(render(JString(toTermString(filter)))) + "]",
              (optRsrc: Option[mTT.Resource]) => {
                //println("evalSubscribeRequest | insertContent | onPost: optRsrc = " + optRsrc)
                BasicLogService.tweet("evalSubscribeRequest | insertContent | onPost: optRsrc = " + optRsrc)
                optRsrc match {
                  case None => ()
                  case Some(_) => {
                    // evalComplete, empty seq of posts
                    val content =
                      ("sessionURI" -> sessionURIStr) ~
                        ("pageOfPosts" -> List[String]())
                    val response = ("msgType" -> "evalComplete") ~ ("content" -> content)
                    //println("evalSubscribeRequest | onPost: response = " + compact(render(response)))
                    BasicLogService.tweet("evalSubscribeRequest | onPost: response = " + compact(render(response)))
                    CometActorMapper.cometMessage(sessionURIStr, compact(render(response)))
                  }
                  case _ => throw new Exception("Unrecognized resource: " + optRsrc)
                }
              })
          }
        }
        case _ => {
          throw new Exception("Unrecognized request: " + compact(render(json)))
        }
      }
    }
  }

  def connectServers(key: String, sessionId: UUID): Unit = {
    connectServers(sessionId)(
      (optRsrc: Option[mTT.Resource]) => {
        println("got response: " + optRsrc)
        //BasicLogService.tweet( "got response: " + optRsrc )
        optRsrc match {
          case None => ()
          case Some(rsrc) => CompletionMapper.complete(key, rsrc.toString)
        }
      })
  }

  def connectServers(sessionId: UUID)(
    onConnection: Option[mTT.Resource] => Unit = //( optRsrc : Option[mTT.Resource] ) => { println( "got response: " + optRsrc ) }
    (optRsrc: Option[mTT.Resource]) => { BasicLogService.tweet("got response: " + optRsrc) }): Unit = {
    // val pulseErql = agentMgr().adminErql( sessionId )
    //     val pulseErspl = agentMgr().adminErspl( sessionId )
    //     ensureServersConnected(
    //       pulseErql,
    //       pulseErspl
    //     )(
    //       onConnection
    //     )
    throw new Exception("connect servers not implemented")
  }

  def sessionPing(json: JValue): String = {
    val sessionURI = (json \ "content" \ "sessionURI").extract[String]
    // TODO: check sessionURI validity

    sessionURI
  }

  def closeSessionRequest(json: JValue): Unit = {
    val sessionURI = (json \ "content" \ "sessionURI").extract[String]

    CometActorMapper.cometMessage(sessionURI, compact(render(
      ("msgType" -> "closeSessionResponse") ~
        ("content" ->
          ("sessionURI" -> sessionURI)))))
  }

  def createNodeUser(email: String, password: String, jsonBlob: String): Unit = {
    val cap = emailToCap(email)
    val capURI = new URI("agent://" + cap)
    val capSelfCnxn = PortableAgentCnxn(capURI, "identity", capURI)

    def handleRsp(): Unit = {
      // Store the email
      put[String](emailLabel, List(capSelfCnxn), cap)
      storeCapByEmail(email)

      // Generate pwmac
      val macInstance = Mac.getInstance("HmacSHA256")
      macInstance.init(new SecretKeySpec("pAss#4$#".getBytes("utf-8"), "HmacSHA256"))
      val pwmac = macInstance.doFinal(password.getBytes("utf-8")).map("%02x" format _).mkString

      // Store pwmac
      post(
        pwmacLabel,
        List(capSelfCnxn),
        pwmac,
        (optRsrc: Option[mTT.Resource]) => {
          BasicLogService.tweet("createNodeUser | onPost1: optRsrc = " + optRsrc)
          optRsrc match {
            case None => ()
            case Some(_) =>
              // Store jsonBlob
              post(
                jsonBlobLabel,
                List(capSelfCnxn),
                jsonBlob,
                (optRsrc: Option[mTT.Resource]) => {
                  BasicLogService.tweet("createNodeUser | onPost2: optRsrc = " + optRsrc)
                  optRsrc match {
                    case None => ()
                    case Some(_) =>
                      // Store alias list containing just the default alias
                      post(
                        aliasListLabel,
                        List(capSelfCnxn),
                        """["alias"]""",
                        (optRsrc: Option[mTT.Resource]) => {
                          BasicLogService.tweet("createNodeUser | onPost3: optRsrc = " + optRsrc)
                          optRsrc match {
                            case None => ()
                            case Some(_) =>
                              // Store default alias
                              post(
                                defaultAliasLabel,
                                List(capSelfCnxn),
                                "alias",
                                (optRsrc: Option[mTT.Resource]) => {
                                  BasicLogService.tweet("createNodeUser | onPost4: optRsrc = " + optRsrc)
                                  optRsrc match {
                                    case None => ()
                                    case Some(_) =>
                                      val aliasCnxn = PortableAgentCnxn(capURI, "alias", capURI)
                                      // Store empty label list on alias cnxn
                                      post(
                                        labelListLabel,
                                        List(aliasCnxn),
                                        """[]""",
                                        (optRsrc: Option[mTT.Resource]) => {
                                          BasicLogService.tweet("createNodeUser | onPost5: optRsrc = " + optRsrc)
                                          //println("createNodeUser | onPost5: optRsrc = " + optRsrc)
                                          optRsrc match {
                                            case None => ()
                                            case Some(x) =>
                                              launchNodeUserBehaviors(aliasCnxn)
                                              // Store empty bi-cnxn list on alias cnxn
                                              post(
                                                biCnxnsListLabel,
                                                List(aliasCnxn),
                                                "")
                                          }
                                        })
                                  }
                                })
                          }
                        })
                  }
                })
          }
        })
    }

    read(
      jsonBlobLabel,
      List(capSelfCnxn),
      (optRsrc: Option[mTT.Resource]) => {
        BasicLogService.tweet("createNodeUser | onRead: optRsrc = " + optRsrc)

        // Check if agent for email exists. If it doesn't, create the agent.
        optRsrc match {
          // colocated
          case Some(mTT.Ground(Bottom)) => {
            handleRsp()
          }
          // distributed
          case Some(mTT.RBoundHM(Some(mTT.Ground(Bottom)), _)) => {
            handleRsp()
          }
          case _ => ()
        }
      })
  }

  def launchNodeUserBehaviors(
    aliasCnxn: PortableAgentCnxn): Unit = {
    import com.biosimilarity.evaluator.distribution.bfactory.BFactoryDefaultServiceContext._
    import com.biosimilarity.evaluator.distribution.bfactory.BFactoryDefaultServiceContext.eServe._

    //println("About to commenceInstance for introduction initiator")
    BasicLogService.tweet("About to commenceInstance for introduction initiator")

    //bFactoryMgr().commenceInstance(
    commenceInstance(
      introductionInitiatorCnxn,
      introductionInitiatorLabel,
      List(aliasCnxn),
      Nil,
      {
        //optRsrc => println( "onCommencement five | " + optRsrc )
        optRsrc => BasicLogService.tweet("onCommencement five | " + optRsrc)
      })

    //println("About to commenceInstance for claimant")
    BasicLogService.tweet("About to commenceInstance for claimant")

    //VerificationBehaviors().launchClaimantBehavior(aliasCnxn.src, agentMgr().feed _)
    VerificationBehaviors().launchClaimantBehavior(aliasCnxn.src, feed _)
  }

  def backupRequest(json: JValue): Unit = {
    val sessionURI = (json \ "content" \ "sessionURI").extract[String]
    //agentMgr().runProcess("mongodump", None, List(), (optRsrc) => {
    runProcess("mongodump", None, List(), (optRsrc) => {
      //println("backupRequest: optRsrc = " + optRsrc)
      BasicLogService.tweet("backupRequest: optRsrc = " + optRsrc)
      CometActorMapper.cometMessage(sessionURI, compact(render(
        ("msgType" -> "backupResponse") ~
          ("content" ->
            ("sessionURI" -> sessionURI)))))
    })
  }

  def restoreRequest(json: JValue): Unit = {
    val sessionURI = (json \ "content" \ "sessionURI").extract[String]
    //agentMgr().runProcess("mongorestore", None, List(), (optRsrc) =>
    //{
    runProcess("mongorestore", None, List(), (optRsrc) => {
      //println("restoreRequest: optRsrc = " + optRsrc)
      BasicLogService.tweet("restoreRequest: optRsrc = " + optRsrc)
      CometActorMapper.cometMessage(sessionURI, compact(render(
        ("msgType" -> "restoreResponse") ~
          ("content" ->
            ("sessionURI" -> sessionURI)))))
    })
  }

  def initiateClaim(json: JValue): Unit = {
    val sessionId = (json \ "content" \ "sessionURI").extract[String]
    val correlationId = (json \ "content" \ "correlationId").extract[String]
    val jvVerifier = json \ "content" \ "verifier"
    val pacVerifier = PortableAgentCnxn(
      new URI((jvVerifier \ "source").extract[String]),
      (jvVerifier \ "label").extract[String],
      new URI((jvVerifier \ "target").extract[String]))
    val jvRelyingParty = json \ "content" \ "relyingParty"
    val pacRelyingParty = PortableAgentCnxn(
      new URI((jvRelyingParty \ "source").extract[String]),
      (jvRelyingParty \ "label").extract[String],
      new URI((jvRelyingParty \ "target").extract[String]))
    val claim = fromTermString((json \ "content" \ "claim").extract[String]).get

    handler.handleinitiateClaim(
      InitiateClaim(
        sessionId,
        correlationId,
        pacVerifier,
        pacRelyingParty,
        claim))
  }
}
