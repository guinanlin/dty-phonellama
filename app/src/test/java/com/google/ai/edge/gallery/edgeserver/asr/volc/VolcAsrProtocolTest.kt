/*
 * Copyright 2026 PhoneLlama
 */

package com.google.ai.edge.gallery.edgeserver.asr.volc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VolcAsrProtocolTest {

  @Test
  fun roundTrip_startConnectionFrame() {
    val json = VolcAsrProtocol.buildConnectionLevelJson()
    val frame =
      VolcAsrProtocol.buildClientJsonFrame(
        VolcAsrProtocol.START_CONNECTION,
        sessionId = null,
        jsonPayload = json,
      )
    val parsed = VolcAsrProtocol.parseFrame(frame)
    assertNotNull(parsed)
    assertEquals(VolcAsrProtocol.MSG_FULL_JSON, parsed!!.messageType)
    assertEquals(VolcAsrProtocol.START_CONNECTION, parsed.event)
    assertEquals(json, String(parsed.payload))
  }

  @Test
  fun roundTrip_taskRequestWithSession() {
    val sessionId = "fe1e96cc-279f-469c-872e-d0127c55b416"
    val audio = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
    val frame =
      VolcAsrProtocol.buildClientAudioFrame(
        VolcAsrProtocol.TASK_REQUEST,
        sessionId = sessionId,
        audioBytes = audio,
      )
    val parsed = VolcAsrProtocol.parseFrame(frame)!!
    assertEquals(VolcAsrProtocol.TASK_REQUEST, parsed.event)
    assertEquals(sessionId, parsed.sessionId)
    assertEquals(audio.size, parsed.payload.size)
  }

  @Test
  fun asrResultJson_extractText() {
    val frame =
      VolcAsrProtocol.buildServerAsrResultFrame(
        VolcAsrProtocol.ASR_RESPONSE,
        sessionId = "sid",
        text = "你好世界",
      )
    val parsed = VolcAsrProtocol.parseFrame(frame)!!
    assertEquals("你好世界", VolcAsrProtocol.extractText(parsed.payload))
  }
}
