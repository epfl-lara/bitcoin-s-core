package org.bitcoins.core.protocol
import org.bitcoins.core.config._
import org.bitcoins.core.config.{ MainNet, RegTest, TestNet3 }
import org.bitcoins.core.crypto.{ ECPublicKey, HashDigest, Sha256Digest, Sha256Hash160Digest }
import org.bitcoins.core.number.{ UInt32, UInt8 }
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.protocol.script._
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.serializers.script.ScriptParser
import org.bitcoins.core.util._
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

sealed abstract class Address {

  /** The network that this address is valid for */
  def networkParameters: NetworkParameters

  /** The string representation of this address */
  def value: String

  /** Every address is derived from a [[HashDigest]] in a [[TransactionOutput]] */
  def hash: HashDigest

  /** The [[ScriptPubKey]] the address represents */
  def scriptPubKey: ScriptPubKey

  override def toString = value
}

sealed abstract class BitcoinAddress extends Address

sealed abstract class P2PKHAddress extends BitcoinAddress {
  /** The base58 string representation of this address */
  override def value: String = {
    val versionByte = networkParameters.p2pkhNetworkByte
    val bytes = versionByte ++ hash.bytes
    val checksum = CryptoUtil.doubleSHA256(bytes).bytes.take(4)
    Base58.encode(bytes ++ checksum)
  }

  override def hash: Sha256Hash160Digest

  override def scriptPubKey: P2PKHScriptPubKey = P2PKHScriptPubKey(hash)

}

sealed abstract class P2SHAddress extends BitcoinAddress {
  /** The base58 string representation of this address */
  override def value: String = {
    val versionByte = networkParameters.p2shNetworkByte
    val bytes = versionByte ++ hash.bytes
    val checksum = CryptoUtil.doubleSHA256(bytes).bytes.take(4)
    Base58.encode(bytes ++ checksum)
  }

  override def scriptPubKey = P2SHScriptPubKey(hash)

  override def hash: Sha256Hash160Digest
}

/**
 * https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki
 */
sealed abstract class Bech32Address extends BitcoinAddress {

  private def logger = BitcoinSLogger.logger

  def hrp: HumanReadablePart

  def data: Seq[UInt8]

  override def networkParameters = hrp.network.get

  override def value: String = {
    val checksum = Bech32Address.createChecksum(hrp, data)
    val all = data ++ checksum
    val encoding = Bech32Address.encodeToString(all)
    hrp.toString + Bech32Address.separator + encoding
  }

  override def scriptPubKey: WitnessScriptPubKey = {
    Bech32Address.fromStringToWitSPK(value).get
  }

  override def hash: Sha256Digest = {
    val byteVector = BitcoinSUtil.toByteVector(scriptPubKey.witnessProgram)
    Sha256Digest(byteVector)
  }

  override def toString = "Bech32Address(" + value + ")"

}

object Bech32Address extends AddressFactory[Bech32Address] {
  private case class Bech32AddressImpl(hrp: HumanReadablePart, data: Seq[UInt8]) extends Bech32Address {
    verifyChecksum(hrp, UInt8.toBytes(data))
  }

  /** Separator used to separate the hrp & data parts of a bech32 addr */
  val separator = '1'

  private val logger = BitcoinSLogger.logger

  def apply(
    witSPK: WitnessScriptPubKey,
    networkParameters: NetworkParameters): Try[Bech32Address] = {
    //we don't encode the wit version or pushop for program into base5
    val prog = UInt8.toUInt8s(witSPK.asmBytes.tail.tail)
    val encoded = Bech32Address.encode(prog)
    val hrp = networkParameters match {
      case _: MainNet => bc
      case _: TestNet3 | _: RegTest => tb
    }
    val witVersion = witSPK.witnessVersion.version.toLong.toShort
    encoded.map(e => Bech32Address(hrp, Seq(UInt8(witVersion)) ++ e))
  }

  def apply(hrp: HumanReadablePart, data: Seq[UInt8]): Bech32Address = {
    Bech32AddressImpl(hrp, data)
  }

  /** Returns a base 5 checksum as specified by BIP173 */
  def createChecksum(hrp: HumanReadablePart, bytes: Seq[UInt8]): Seq[UInt8] = {
    val values: Seq[UInt8] = hrpExpand(hrp) ++ bytes
    val z = UInt8.zero
    val polymod: Long = polyMod(values ++ Seq(z, z, z, z, z, z)) ^ 1
    //[(polymod >> 5 * (5 - i)) & 31 for i in range(6)]
    val result: Seq[UInt8] = 0.until(6).map { i =>
      val u = UInt8(i.toShort)
      val five = UInt8(5.toShort)
      //((polymod >> five * (five - u)) & UInt8(31.toShort))
      UInt8(((polymod >> 5 * (5 - i)) & 31).toShort)
    }
    result
  }

  def hrpExpand(hrp: HumanReadablePart): Seq[UInt8] = {
    val x: ByteVector = hrp.bytes.map { b: Byte =>
      (b >> 5).toByte
    }
    val withZero: ByteVector = x ++ ByteVector.low(1)

    val y: ByteVector = hrp.bytes.map { char =>
      (char & 0x1f).toByte
    }
    val result = UInt8.toUInt8s(withZero ++ y)
    result
  }

  private def generators: Seq[Long] = Seq(
    UInt32("3b6a57b2").toLong,
    UInt32("26508e6d").toLong, UInt32("1ea119fa").toLong,
    UInt32("3d4233dd").toLong, UInt32("2a1462b3").toLong)

  def polyMod(bytes: Seq[UInt8]): Long = {
    var chk: Long = 1
    bytes.map { v =>
      val b = chk >> 25
      //chk = (chk & 0x1ffffff) << 5 ^ v
      chk = (chk & 0x1ffffff) << 5 ^ v.toLong
      0.until(5).map { i: Int =>
        //chk ^= GEN[i] if ((b >> i) & 1) else 0
        if (((b >> i) & 1) == 1) {
          chk = chk ^ generators(i)
        }
      }
    }
    chk
  }

  def verifyChecksum(hrp: HumanReadablePart, data: ByteVector): Boolean = {
    val u8s = UInt8.toUInt8s(data)
    verifyCheckSum(hrp, u8s)
  }

  def verifyCheckSum(hrp: HumanReadablePart, u8s: Seq[UInt8]): Boolean = {
    polyMod(hrpExpand(hrp) ++ u8s) == 1
  }

  private val u32Five = UInt32(5)
  private val u32Eight = UInt32(8)

  /** Converts a byte array from base 8 to base 5 */
  def encode(bytes: Seq[UInt8]): Try[Seq[UInt8]] = {
    NumberUtil.convertUInt8s(bytes, u32Eight, u32Five, true)
  }
  /** Decodes a byte array from base 5 to base 8 */
  def decodeToBase8(b: Seq[UInt8]): Try[Seq[UInt8]] = {
    NumberUtil.convertUInt8s(b, u32Five, u32Eight, false)
  }

  /** Tries to convert the given string a to a [[org.bitcoins.core.protocol.script.WitnessScriptPubKey]] */
  def fromStringToWitSPK(string: String): Try[WitnessScriptPubKey] = {
    val decoded = fromString(string)
    decoded.flatMap {
      case bec32Addr =>
        val bytes = UInt8.toBytes(bec32Addr.data)
        val (v, prog) = (bytes.head, bytes.tail)
        val convertedProg = NumberUtil.convertBytes(prog, u32Five, u32Eight, false)
        val progBytes = convertedProg.map(UInt8.toBytes(_))
        val witVersion = WitnessVersion(v)
        progBytes.flatMap { prog =>
          val pushOp = BitcoinScriptUtil.calculatePushOp(prog)
          witVersion match {
            case Some(v) =>
              WitnessScriptPubKey(Seq(v.version) ++ pushOp ++ Seq(ScriptConstant(prog))) match {
                case Some(spk) => Success(spk)
                case None => Failure(new IllegalArgumentException("Failed to decode bech32 into a witSPK"))
              }
            case None => Failure(new IllegalArgumentException("Witness version was not valid, got: " + v))
          }

        }
    }
  }
  /** Takes a base32 byte array and encodes it to a string */
  def encodeToString(b: Seq[UInt8]): String = {
    b.map(b => charset(b.toInt)).mkString
  }
  /** Decodes bech32 string to the [[HumanReadablePart]] & data part */
  override def fromString(str: String): Try[Bech32Address] = {
    val sepIndexes = str.zipWithIndex.filter(_._1 == separator)
    if (str.size > 90 || str.size < 8) {
      Failure(new IllegalArgumentException("bech32 payloads must be betwee 8 and 90 chars, got: " + str.size))
    } else if (sepIndexes.isEmpty) {
      Failure(new IllegalArgumentException("Bech32 address did not have the correct separator"))
    } else {
      val sepIndex = sepIndexes.last._2
      val (hrp, data) = (str.take(sepIndex), str.splitAt(sepIndex + 1)._2)
      if (hrp.size < 1 || data.size < 6) {
        Failure(new IllegalArgumentException("Hrp/data too short"))
      } else {
        val hrpValid = checkHrpValidity(hrp)
        val dataValid = checkDataValidity(data)
        val isChecksumValid: Try[ByteVector] = hrpValid.flatMap { h =>
          dataValid.flatMap { d =>
            if (verifyChecksum(h, d)) {
              if (d.size < 6) Success(ByteVector.empty)
              else Success(d.take(d.size - 6))
            } else Failure(new IllegalArgumentException("Checksum was invalid on the bech32 address"))
          }
        }
        isChecksumValid.flatMap { d: ByteVector =>
          val u8s = UInt8.toUInt8s(d)
          hrpValid.map(h => Bech32Address(h, u8s))
        }
      }
    }
  }

  override def fromScriptPubKey(spk: ScriptPubKey, np: NetworkParameters): Try[Bech32Address] = spk match {
    case witSPK: WitnessScriptPubKey => Bech32Address.fromScriptPubKey(witSPK, np)
    case x @ (_: P2PKScriptPubKey | _: P2PKHScriptPubKey
      | _: MultiSignatureScriptPubKey | _: P2SHScriptPubKey
      | _: LockTimeScriptPubKey | _: WitnessScriptPubKey
      | _: EscrowTimeoutScriptPubKey | _: NonStandardScriptPubKey
      | _: WitnessCommitment | _: UnassignedWitnessScriptPubKey | EmptyScriptPubKey) =>
      Failure(new IllegalArgumentException("Cannot create a address for the scriptPubKey: " + x))
  }

  /** Checks if the possible human readable part follows BIP173 rules */
  private def checkHrpValidity(hrp: String): Try[HumanReadablePart] = {
    @tailrec
    def loop(remaining: List[Char], accum: Seq[UInt8], isLower: Boolean, isUpper: Boolean): Try[Seq[UInt8]] = remaining match {
      case h :: t =>
        if (h < 33 || h > 126) {
          Failure(new IllegalArgumentException("Invalid character range for hrp, got: " + hrp))
        } else if (isLower && isUpper) {
          Failure(new IllegalArgumentException("HRP had mixed case, got: " + hrp))
        } else {
          loop(t, UInt8(h.toByte) +: accum, h.isLower || isLower, h.isUpper || isUpper)
        }
      case Nil =>
        if (isLower && isUpper) {
          Failure(new IllegalArgumentException("HRP had mixed case, got: " + hrp))
        } else {
          Success(accum.reverse)
        }
    }

    loop(hrp.toCharArray.toList, Nil, false, false).flatMap { _ =>
      Success(HumanReadablePart(hrp.toLowerCase))
    }
  }

  /**
   * Takes in the data portion of a bech32 address and decodes it to a byte array
   * It also checks the validity of the data portion according to BIP173
   */
  def checkDataValidity(data: String): Try[ByteVector] = {
    @tailrec
    def loop(remaining: List[Char], accum: ByteVector, hasUpper: Boolean, hasLower: Boolean): Try[ByteVector] = remaining match {
      case Nil => Success(accum.reverse)
      case h :: t =>
        if (!charset.contains(h.toLower)) {
          Failure(new IllegalArgumentException("Invalid character in data of bech32 address, got: " + h))
        } else {
          if ((h.isUpper && hasLower) || (h.isLower && hasUpper)) {
            Failure(new IllegalArgumentException("Cannot have mixed case for bech32 address"))
          } else {
            val byte = charset.indexOf(h.toLower).toByte
            require(byte >= 0 && byte < 32, "Not in valid range, got: " + byte)
            loop(t, byte +: accum, h.isUpper || hasUpper, h.isLower || hasLower)
          }
        }
    }
    val payload: Try[ByteVector] = loop(data.toCharArray.toList, ByteVector.empty,
      false, false)
    payload
  }

  /** https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki#bech32 */
  def charset: Seq[Char] = Seq('q', 'p', 'z', 'r', 'y', '9', 'x', '8',
    'g', 'f', '2', 't', 'v', 'd', 'w', '0',
    's', '3', 'j', 'n', '5', '4', 'k', 'h',
    'c', 'e', '6', 'm', 'u', 'a', '7', 'l')
}

object P2PKHAddress extends AddressFactory[P2PKHAddress] {
  private case class P2PKHAddressImpl(
    hash: Sha256Hash160Digest,
    networkParameters: NetworkParameters) extends P2PKHAddress

  def apply(hash: Sha256Hash160Digest, network: NetworkParameters): P2PKHAddress = P2PKHAddressImpl(hash, network)

  def apply(pubKey: ECPublicKey, networkParameters: NetworkParameters): P2PKHAddress = {
    val hash = CryptoUtil.sha256Hash160(pubKey.bytes)
    P2PKHAddress(hash, networkParameters)
  }

  def apply(spk: P2PKHScriptPubKey, networkParameters: NetworkParameters): P2PKHAddress = {
    P2PKHAddress(spk.pubKeyHash, networkParameters)
  }

  override def fromString(address: String): Try[P2PKHAddress] = {
    val decodeCheckP2PKH: Try[ByteVector] = Base58.decodeCheck(address)
    decodeCheckP2PKH.flatMap { bytes =>
      val networkBytes: Option[(NetworkParameters, ByteVector)] = Networks.knownNetworks.map(n => (n, n.p2pkhNetworkByte))
        .find {
          case (_, bs) =>
            bytes.startsWith(bs)
        }
      val result: Option[P2PKHAddress] = networkBytes.map {
        case (network, p2pkhNetworkBytes) =>
          val payloadSize = bytes.size - p2pkhNetworkBytes.size
          require(payloadSize == 20, s"Payload of a P2PKH address must be 20 bytes in size, got $payloadSize")
          val payload = bytes.slice(p2pkhNetworkBytes.size, bytes.size)
          P2PKHAddress(Sha256Hash160Digest(payload), network)
      }
      result match {
        case Some(addr) => Success(addr)
        case None => Failure(new IllegalArgumentException(s"Given address was not a valid P2PKH address, got: $address"))
      }
    }
  }

  override def fromScriptPubKey(spk: ScriptPubKey, np: NetworkParameters): Try[P2PKHAddress] = spk match {
    case p2pkh: P2PKHScriptPubKey => Success(P2PKHAddress(p2pkh, np))
    case x @ (_: P2PKScriptPubKey | _: MultiSignatureScriptPubKey
      | _: P2SHScriptPubKey | _: LockTimeScriptPubKey
      | _: WitnessScriptPubKey | _: EscrowTimeoutScriptPubKey
      | _: NonStandardScriptPubKey | _: WitnessCommitment
      | _: UnassignedWitnessScriptPubKey | EmptyScriptPubKey) =>
      Failure(new IllegalArgumentException("Cannot create a address for the scriptPubKey: " + x))
  }
}

object P2SHAddress extends AddressFactory[P2SHAddress] {
  private case class P2SHAddressImpl(
    hash: Sha256Hash160Digest,
    networkParameters: NetworkParameters) extends P2SHAddress

  /**
   * Creates a [[P2SHScriptPubKey]] from the given [[ScriptPubKey]],
   * then creates an address from that [[P2SHScriptPubKey]]
   */
  def apply(scriptPubKey: ScriptPubKey, network: NetworkParameters): P2SHAddress = {
    val p2shScriptPubKey = P2SHScriptPubKey(scriptPubKey)
    P2SHAddress(p2shScriptPubKey, network)
  }

  def apply(p2shScriptPubKey: P2SHScriptPubKey, network: NetworkParameters): P2SHAddress = P2SHAddress(p2shScriptPubKey.scriptHash, network)

  def apply(hash: Sha256Hash160Digest, network: NetworkParameters): P2SHAddress = P2SHAddressImpl(hash, network)

  override def fromString(address: String): Try[P2SHAddress] = {
    val decodeCheckP2SH: Try[ByteVector] = Base58.decodeCheck(address)
    decodeCheckP2SH.flatMap { bytes =>
      val networkBytes: Option[(NetworkParameters, ByteVector)] = Networks.knownNetworks.map(n => (n, n.p2shNetworkByte))
        .find {
          case (_, bs) =>
            bytes.startsWith(bs)
        }
      val result: Option[P2SHAddress] = networkBytes.map {
        case (network, p2shNetworkBytes) =>
          val payloadSize = bytes.size - p2shNetworkBytes.size
          require(payloadSize == 20, s"Payload of a P2PKH address must be 20 bytes in size, got $payloadSize")
          val payload = bytes.slice(p2shNetworkBytes.size, bytes.size)
          P2SHAddress(Sha256Hash160Digest(payload), network)
      }
      result match {
        case Some(addr) => Success(addr)
        case None => Failure(new IllegalArgumentException(s"Given address was not a valid P2PKH address, got: $address"))
      }
    }
  }

  override def fromScriptPubKey(spk: ScriptPubKey, np: NetworkParameters): Try[P2SHAddress] = spk match {
    case p2sh: P2SHScriptPubKey => Success(P2SHAddress(p2sh, np))
    case x @ (_: P2PKScriptPubKey | _: P2PKHScriptPubKey | _: MultiSignatureScriptPubKey
      | _: LockTimeScriptPubKey | _: WitnessScriptPubKey
      | _: EscrowTimeoutScriptPubKey | _: NonStandardScriptPubKey
      | _: WitnessCommitment | _: UnassignedWitnessScriptPubKey | EmptyScriptPubKey) =>
      Failure(new IllegalArgumentException("Cannot create a address for the scriptPubKey: " + x))
  }
}

object BitcoinAddress extends AddressFactory[BitcoinAddress] {
  private val logger = BitcoinSLogger.logger

  /** Creates a [[BitcoinAddress]] from the given string value */
  def apply(value: String): Try[BitcoinAddress] = fromString(value)

  override def fromString(value: String): Try[BitcoinAddress] = {
    val p2pkhTry = P2PKHAddress.fromString(value)
    if (p2pkhTry.isSuccess) {
      p2pkhTry
    } else {
      val p2shTry = P2SHAddress.fromString(value)
      if (p2shTry.isSuccess) {
        p2shTry
      } else {
        val bech32Try = Bech32Address.fromString(value)
        if (bech32Try.isSuccess) {
          bech32Try
        } else {
          Failure(new IllegalArgumentException(s"Could not decode the given value to a BitcoinAddress, got: $value"))
        }
      }
    }
  }

  override def fromScriptPubKey(spk: ScriptPubKey, np: NetworkParameters): Try[BitcoinAddress] = spk match {
    case p2pkh: P2PKHScriptPubKey => Success(P2PKHAddress(p2pkh, np))
    case p2sh: P2SHScriptPubKey => Success(P2SHAddress(p2sh, np))
    case witSPK: WitnessScriptPubKey => Bech32Address(witSPK, np)
    case x @ (_: P2PKScriptPubKey | _: MultiSignatureScriptPubKey | _: LockTimeScriptPubKey
      | _: EscrowTimeoutScriptPubKey | _: NonStandardScriptPubKey
      | _: WitnessCommitment | _: UnassignedWitnessScriptPubKey | EmptyScriptPubKey) =>
      Failure(new IllegalArgumentException("Cannot create a address for the scriptPubKey: " + x))
  }

}

object Address extends AddressFactory[Address] {

  def fromBytes(bytes: ByteVector): Try[Address] = {
    val encoded = Base58.encode(bytes)
    BitcoinAddress.fromString(encoded)
  }

  def fromHex(hex: String): Try[Address] = fromBytes(BitcoinSUtil.decodeHex(hex))

  def apply(bytes: ByteVector): Try[Address] = fromBytes(bytes)

  def apply(str: String): Try[Address] = fromString(str)

  override def fromString(str: String): Try[Address] = {
    BitcoinAddress.fromString(str)
  }
  override def fromScriptPubKey(spk: ScriptPubKey, network: NetworkParameters): Try[Address] = network match {
    case bitcoinNetwork: BitcoinNetwork => BitcoinAddress.fromScriptPubKey(spk, network)
  }
  def apply(spk: ScriptPubKey, networkParameters: NetworkParameters): Try[Address] = {
    fromScriptPubKey(spk, networkParameters)
  }
}
