package org.scalacoin.marshallers.script

import org.scalacoin.script.constant._
import org.scalacoin.script.crypto.OP_CHECKMULTISIG
import org.scalatest.FlatSpec
import org.scalatest.MustMatchers
import org.scalacoin.protocol.script.ScriptSignature

import spray.json._
import DefaultJsonProtocol._
/**
 * Created by chris on 12/27/15.
 */
class ScriptSignatureMarshallerTest extends FlatSpec with MustMatchers {

  val json =
    """
      |{
      | "asm" : "0 30440220028c02f14654a0cc12c7e3229adb09d5d35bebb6ba1057e39adb1b2706607b0d0220564fab12c6da3d5acef332406027a7ff1cbba980175ffd880e1ba1bf40598f6b01 30450221009362f8d67b60773745e983d07ba10efbe566127e244b724385b2ca2e47292dda022033def393954c320653843555ddbe7679b35cc1cacfe1dad923977de8cd6cc6d701 5221025e9adcc3d65c11346c8a6069d6ebf5b51b348d1d6dc4b95e67480c34dc0bc75c21030585b3c80f4964bf0820086feda57c8e49fa1eab925db7c04c985467973df96521037753a5e3e9c4717d3f81706b38a6fb82b5fb89d29e580d7b98a37fea8cdefcad53ae",
      | "hex" : "004730440220028c02f14654a0cc12c7e3229adb09d5d35bebb6ba1057e39adb1b2706607b0d0220564fab12c6da3d5acef332406027a7ff1cbba980175ffd880e1ba1bf40598f6b014830450221009362f8d67b60773745e983d07ba10efbe566127e244b724385b2ca2e47292dda022033def393954c320653843555ddbe7679b35cc1cacfe1dad923977de8cd6cc6d7014c695221025e9adcc3d65c11346c8a6069d6ebf5b51b348d1d6dc4b95e67480c34dc0bc75c21030585b3c80f4964bf0820086feda57c8e49fa1eab925db7c04c985467973df96521037753a5e3e9c4717d3f81706b38a6fb82b5fb89d29e580d7b98a37fea8cdefcad53ae"
      |}
    """.stripMargin


  "ScriptSignatureMarshaller" must "parse a json script signature object" in {
    val scriptSig : ScriptSignature = ScriptSignatureMarshaller.ScriptSignatureFormatter.read(json.parseJson)
    scriptSig.asm must be (List(OP_0, BytesToPushOntoStackImpl(71),
      ScriptConstantImpl("30440220028c02f14654a0cc12c7e3229adb09d5d35bebb6ba1057e39adb1b2706607b0d0220564fab12c6da3d5acef332406027a7ff1cbba980175ffd880e1ba1bf40598f6b01"),
      BytesToPushOntoStackImpl(72),
      ScriptConstantImpl("30450221009362f8d67b60773745e983d07ba10efbe566127e244b724385b2ca2e47292dda022033def393954c320653843555ddbe7679b35cc1cacfe1dad923977de8cd6cc6d701"),
      OP_PUSHDATA1, BytesToPushOntoStackImpl(105), OP_2,
      BytesToPushOntoStackImpl(33),
      ScriptConstantImpl("025e9adcc3d65c11346c8a6069d6ebf5b51b348d1d6dc4b95e67480c34dc0bc75c"),
      BytesToPushOntoStackImpl(33),
      ScriptConstantImpl("030585b3c80f4964bf0820086feda57c8e49fa1eab925db7c04c985467973df965"),
      BytesToPushOntoStackImpl(33),
      ScriptConstantImpl("037753a5e3e9c4717d3f81706b38a6fb82b5fb89d29e580d7b98a37fea8cdefcad"), OP_3,
      OP_CHECKMULTISIG
    ))
    scriptSig.hex must be ("004730440220028c02f14654a0cc12c7e3229adb09d5d35bebb6ba1057e39adb1b2706607b0d0220564fab12c6da3d5acef332406027a7ff1cbba980175ffd880e1ba1bf40598f6b014830450221009362f8d67b60773745e983d07ba10efbe566127e244b724385b2ca2e47292dda022033def393954c320653843555ddbe7679b35cc1cacfe1dad923977de8cd6cc6d7014c695221025e9adcc3d65c11346c8a6069d6ebf5b51b348d1d6dc4b95e67480c34dc0bc75c21030585b3c80f4964bf0820086feda57c8e49fa1eab925db7c04c985467973df96521037753a5e3e9c4717d3f81706b38a6fb82b5fb89d29e580d7b98a37fea8cdefcad53ae")
  }
}
