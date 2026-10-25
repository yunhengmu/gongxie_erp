package org.dromara.ai.core.channel;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import org.dromara.ai.config.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 渠道入站图片装配：飞书 / 钉钉图片不带文件名，MIME 必须按字节魔数识别。
 */
@DisplayName("渠道入站媒体装配")
class AiChannelMediaAssemblerTest {

    @Test
    @DisplayName("无文件名的 JPEG 图片按魔数识别为 image/jpeg")
    void shouldDetectJpegByMagicBytes() {
        AiChannelRuntime runtime = Mockito.mock(AiChannelRuntime.class);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00};
        when(runtime.downloadMedia(eq(ChannelMediaMetadata.KIND_IMAGE), eq("om_1"),
            eq("img_key"), isNull(), isNull(), anyInt())).thenReturn(jpeg);

        AiChannelMediaAssembler assembler = new AiChannelMediaAssembler(new AiProperties());
        Msg msg = Msg.builder()
            .role(MsgRole.USER)
            .textContent("[image]")
            .metadata(Map.of(
                ChannelMediaMetadata.KIND, ChannelMediaMetadata.KIND_IMAGE,
                ChannelMediaMetadata.ID, "img_key",
                "channelMessageId", "om_1"))
            .build();
        InboundMessage inbound = InboundMessage.builder(
            "feishu_robot", new Peer(PeerKind.DIRECT, "oc_1"), List.of(msg)).build();

        AiChannelMediaAssembler.ChannelContent content = assembler.assemble(runtime, inbound);

        assertEquals(1, content.blocks().size());
        ContentBlock block = content.blocks().get(0);
        ImageBlock image = assertInstanceOf(ImageBlock.class, block);
        Base64Source source = assertInstanceOf(Base64Source.class, image.getSource());
        assertEquals("image/jpeg", source.getMediaType());
    }
}
