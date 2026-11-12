package com.example.voiceagent

import com.example.voiceagent.Command.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun p(s: String) = CommandParser.parse(s)

    @Test fun englishCalls() {
        assertEquals(Call("mom"), p("Call Mom"))
        assertEquals(Call("rahul"), p("Please call Rahul now"))
        assertEquals(Call("98765 43210"), p("call 98765 43210"))
        assertEquals(Call("amit"), p("give Amit a call"))
        assertEquals(WaCall("dad", true), p("WhatsApp video call Dad"))
        assertEquals(WaCall("priya", false), p("call Priya on WhatsApp"))
        assertEquals(WaCall("mom", true), p("video call mom"))
    }

    @Test fun englishMessages() {
        assertEquals(WaMessage("rahul", "hello"), p("Send hello to Rahul on WhatsApp"))
        assertEquals(WaMessage("rahul", "i'm late"), p("send a message to Rahul saying I'm late"))
        assertEquals(WaMessage("priya", "i reached"), p("message Priya on WhatsApp that I reached"))
        assertEquals(WaMessage("rahul", "hi"), p("send Rahul a message saying hi"))
        assertEquals(WaUnsplit("priya i'm running late"), p("Tell Priya I'm running late"))
        assertEquals(WaUnsplit("amit"), p("Message Amit on WhatsApp"))
        assertEquals(WaMessage("amit", null), p("send a WhatsApp message to Amit"))
        assertEquals(Sms("rahul", "i'll call later"), p("Send an SMS to Rahul saying I'll call later"))
        assertEquals(WaMessage("rahul", null), p("open chat with Rahul"))
    }

    @Test fun englishFiles() {
        val c = p("Send the latest PDF to Rahul on WhatsApp") as ShareFile
        assertEquals("rahul", c.who); assertEquals(FileKind.PDF, c.query.kind); assertTrue(c.query.latest)
        val d = p("share the last photo with Priya") as ShareFile
        assertEquals(FileKind.IMAGE, d.query.kind)
        val e = p("send the file named budget to Amit") as ShareFile
        assertEquals(listOf("budget"), e.query.keywords)
        val f = p("send my hotel booking voucher to Mom on whatsapp") as ShareFile
        assertEquals(listOf("hotel", "booking", "voucher"), f.query.keywords)
        assertEquals(ShareThis("mom"), p("send this to Mom"))
        assertEquals(ShareThis("rahul"), p("share this photo with Rahul on WhatsApp"))
    }

    @Test fun englishOther() {
        assertEquals(OpenApp("youtube"), p("Open YouTube"))
        assertEquals(OpenApp("whatsapp"), p("open WhatsApp"))
        assertEquals(OpenUrl("google.com"), p("open google.com"))
        assertEquals(Play("arijit songs", null), p("Play Arijit songs"))
        assertEquals(Play("lofi", "spotify"), p("play lofi on spotify"))
        assertEquals(Search("hotels in goa", null), p("Search hotels in Goa"))
        assertEquals(Navigate("pune railway station"), p("Navigate to Pune railway station"))
        assertEquals(Toggle("torch", true), p("Turn on the torch"))
        assertEquals(Toggle("torch", false), p("turn off flashlight"))
        assertEquals(Toggle("wifi", false), p("Turn off Wi-Fi"))
        assertEquals(Toggle("bluetooth", true), p("switch on bluetooth"))
        assertEquals(Volume("up"), p("Volume up"))
        assertEquals(Volume("silent"), p("put the phone on silent"))
        assertEquals(Alarm(6, 30), p("Set an alarm for 6:30 a.m."))
        assertEquals(Alarm(19, 0), p("set alarm at 7 pm"))
        assertEquals(Timer(600), p("Set a timer for 10 minutes"))
        assertEquals(Time, p("what's the time"))
        assertEquals(Battery, p("battery"))
        assertEquals(ReadScreen, p("read the screen"))
        assertEquals(Global("screenshot"), p("take a screenshot"))
        assertEquals(Global("back"), p("go back"))
        assertEquals(Global("lock"), p("lock the phone"))
        assertEquals(SetLanguage("mr-IN"), p("speak Marathi"))
        assertEquals(Help, p("help"))
        assertEquals(Stop, p("stop"))
        assertEquals(null, p("what is the meaning of life"))
    }

    @Test fun marathiCalls() {
        assertEquals(Call("आईला"), p("आईला फोन लाव"))
        assertEquals(Call("राहुलला"), p("राहुलला कॉल कर"))
        assertEquals(Call("9876543210"), p("९८७६५४३२१० ला फोन लाव"))
        assertEquals(WaCall("बाबांना", true), p("बाबांना व्हिडिओ कॉल कर"))
        assertEquals(WaCall("प्रियाला", false), p("प्रियाला व्हॉट्सॲपवर कॉल कर"))
    }

    @Test fun marathiMessages() {
        assertEquals(WaMessage("राहुलला", "मी पोहोचलो"), p("राहुलला व्हॉट्सॲपवर मेसेज पाठव की मी पोहोचलो"))
        assertEquals(WaMessage("प्रियाला", "मी उशिरा येतोय"), p("प्रियाला सांग की मी उशिरा येतोय"))
        assertEquals(WaMessage("राहुलला", "मी उशिरा येतोय"), p("राहुलला मी उशिरा येतोय असं सांग"))
        assertEquals(WaMessage("अमितला", null), p("अमितला व्हॉट्सॲप मेसेज पाठव"))
        assertEquals(WaMessage("राहुलला", "हॅलो"), p("व्हाट्सअपवर राहुलला हॅलो पाठव"))
    }

    @Test fun marathiFilesAndOther() {
        val f = p("शेवटची पीडीएफ अमितला व्हॉट्सॲपवर पाठव") as ShareFile
        assertEquals("अमितला", f.who); assertEquals(FileKind.PDF, f.query.kind); assertTrue(f.query.latest)
        assertEquals(emptyList<String>(), f.query.keywords)
        assertEquals(ShareThis("आईला"), p("हा फोटो आईला पाठव"))
        val t = p("तिकीट राहुलला पाठव") as ShareFile
        assertEquals(listOf("ticket"), t.query.keywords)
        assertEquals(OpenApp("यूट्यूब"), p("यूट्यूब उघड"))
        assertEquals(Toggle("torch", true), p("टॉर्च चालू कर"))
        assertEquals(Toggle("torch", false), p("टॉर्च बंद कर"))
        assertEquals(Volume("down"), p("आवाज कमी कर"))
        assertEquals(Alarm(6, 30), p("सकाळी साडे ६ चा अलार्म लाव"))
        assertEquals(Alarm(21, 0), p("रात्री ९ वाजता अलार्म लाव"))
        assertEquals(Timer(600), p("१० मिनिटांचा टायमर लाव"))
        assertEquals(Time, p("किती वाजले"))
        assertEquals(ReadScreen, p("स्क्रीनवर काय आहे वाचून दाखव"))
        assertEquals(SetLanguage("en-IN"), p("इंग्रजीत बोल"))
        assertEquals(Global("lock"), p("फोन लॉक कर"))
        assertEquals(Play("मराठी गाणी", null), p("यूट्यूबवर मराठी गाणी लाव"))
        assertEquals(Stop, p("थांब"))
    }

    @Test fun nameMatching() {
        assertTrue(Text.nameScore("राहुल", "Rahul") > 0.9)
        assertTrue(Text.nameScore("राहुल", "Rahul Sharma") > 0.9)
        assertTrue(Text.nameScore("प्रिया", "Priya") > 0.9)
        assertTrue(Text.nameScore("अमित", "Amit Patil") > 0.9)
        assertTrue(Text.nameScore("Rahul", "Priya") < 0.5)
        assertTrue(Text.nameScore("यूट्यूब", "YouTube") > 0.75)
        assertEquals("राहुल", Text.stripMarathiSuffix("राहुलला"))
        assertEquals("rahul", Text.fold("राहुल"))
    }
}
