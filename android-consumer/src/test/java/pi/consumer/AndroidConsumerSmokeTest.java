package pi.consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import pi.agent.core.Agent;
import pi.agent.core.AgentThinkingLevel;
import pi.agent.core.AgentState;

public final class AndroidConsumerSmokeTest {
    @Test
    public void agentIsConstructibleFromAndroidConsumer() {
        Agent agent = new Agent();
        AgentState state = agent.getState();

        assertNotNull(state);
        assertEquals("", state.getSystemPrompt());
        assertEquals(AgentThinkingLevel.OFF, state.getThinkingLevel());
        assertFalse(state.isStreaming());
    }
}
