package com.example.voiceagent

import com.example.voiceagent.Command.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real replies from the Gemma 4 model file the app downloads, run through the app's parser. */
class PlanParserTest {

    private fun plan(raw: String, said: String) = PlanParser.parse(raw, said)!!

    @Test fun multiStepAndMessages() {
        val p = plan("""{"steps":[{"do":"call","who":"Neha"},{"do":"whatsapp","who":"Neha","text":"I called"}],"reply": ""}""",
            "call neha and after that message her that i called")
        assertEquals(listOf(Call("Neha"), WaMessage("Neha", "I called")), p.steps)
        assertEquals("", p.reply)
    }

    @Test fun fixesDurationArithmetic() {
        // The model said 300 seconds for "half an hour".
        val p = plan("""{"steps":[{"do":"timer","seconds":300},{"do":"play","query":"bhajans"}],"reply":""}""",
            "set a timer for half an hour and play some bhajans")
        assertEquals(listOf(Timer(1800), Play("bhajans", null)), p.steps)
    }

    @Test fun recoversDroppedMarathiStep() {
        // The model forgot "…आणि मग टॉर्च चालू कर".
        val p = plan("""{"steps":[{"do":"whatsapp","who":"अमित","text":"उद्या भेटूया"}],"reply":"अमितला व्हॉट्सॲपवर मेसेज केला."}""",
            "अमितला सांग उद्या भेटूया आणि मग टॉर्च चालू कर")
        assertEquals(listOf(WaMessage("अमित", "उद्या भेटूया"), Toggle("torch", true)), p.steps)
        assertEquals("", p.reply)   // chatter is dropped when there are steps
    }

    @Test fun filesAndAlarms() {
        val p = plan("""{"steps":[{"do":"send_file","who":"Amit","kind":"document","words":"boarding pass","latest":true},{"do":"alarm","hour":5,"minute":0}],"reply":""}""",
            "send my boarding pass to amit and set an alarm for 5 am")
        assertEquals(listOf(ShareFile("Amit", FileQuery(FileKind.DOCUMENT, listOf("boarding", "pass"), true)), Alarm(5, 0)), p.steps)
    }

    @Test fun alarmUsesWordsNearAlarmOnly() {
        val p = plan("""{"steps":[{"do":"whatsapp","who":"Rahul","text":"I'll reach by 7"},{"do":"alarm","hour":7,"minute":0}],"reply":""}""",
            "tell rahul I'll reach by 7 and set an alarm for 6:30 am")
        assertEquals(Alarm(6, 30), p.steps[1])
    }

    @Test fun answersAndQuestions() {
        assertEquals("18 percent of 2500 is 450.", plan("""{"steps":[],"reply":"18 percent of 2500 is 450."}""", "how much is 18 percent of 2500").reply)
        assertEquals(listOf(Battery), plan("""{"steps":[{"do":"battery","level":true}],"reply":"..."}""", "how much battery do i have left").steps)
        assertEquals(listOf(Stop), plan("""{"steps": [{"do": "stop"}], "reply": ""}""", "cancel it").steps)
    }

    @Test fun messyOutputStillParses() {
        val p = plan("Sure! ```json\n{\"steps\":[{\"do\":\"torch\",\"on\":false}],\"reply\":\"\"}\n```", "torch off")
        assertEquals(listOf(Toggle("torch", false)), p.steps)
    }
}
