package io.groundedrag.generation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/** One exchange in a conversation, as remembered for follow-up questions. */
public record Turn(String question, String answer) {

    /** Pairs each user message with the assistant reply that follows it; unanswered questions are dropped. */
    public static List<Turn> fromMessages(List<Message> messages) {
        List<Turn> out = new ArrayList<>();
        String pending = null;
        for (Message m : messages) {
            if (m.getMessageType() == MessageType.USER) {
                pending = m.getText();
            } else if (m.getMessageType() == MessageType.ASSISTANT && pending != null) {
                out.add(new Turn(pending, m.getText() == null ? "" : m.getText()));
                pending = null;
            }
        }
        return out;
    }
}
