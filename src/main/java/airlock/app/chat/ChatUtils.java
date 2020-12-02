package airlock.app.chat;

import airlock.Urbit;

import java.time.Instant;

public class ChatUtils {

	public static MessagePayload createMessagePayload(String path, String uid, String author, long when, String textContent) {
		return new MessagePayload(
				new Message(
						new Envelope(
								uid,
								1, // dummy value
								author,
								when,
								new Letter(
										textContent
								)
						),
						path
				)
		);
	}

	public static MessagePayload createMessagePayload(String path, String author, long when, String textContent) {
		return ChatUtils.createMessagePayload(path, Urbit.uid(), author, when, textContent);
	}

	public static MessagePayload createMessagePayload(String path, String author, String textContent) {
		return ChatUtils.createMessagePayload(path, Urbit.uid(), author, Instant.now().toEpochMilli(), textContent);
	}

}