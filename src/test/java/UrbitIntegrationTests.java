import airlock.*;
import airlock.agent.chat.ChatUpdate;
import airlock.agent.chat.ChatUtils;
import airlock.agent.graph.GraphStoreAgent;
import airlock.agent.graph.Resource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import static airlock.AirlockUtils.map2json;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class UrbitIntegrationTests {

	private static Urbit urbit;

	private static CompletableFuture<PokeResponse> futureChatPokeResponse1;
	private static List<SubscribeEvent> subscribeToMailboxEvents;

	private static List<SubscribeEvent> primaryChatSubscriptionEvents;
	private static GraphStoreAgent graphStoreAgent;
	private final CompletableFuture<String> futurePrimaryChatMessage = new CompletableFuture<>();
	private final String primaryChatViewTestMessage = "Primary Chat view Test Message" + Instant.now().toEpochMilli();


	final Predicate<SubscribeEvent> onlyPrimaryChatUpdate = subscribeEvent ->       // anything that is:
			subscribeEvent.eventType.equals(SubscribeEvent.EventType.UPDATE)  // an update event
					&& subscribeEvent.updateJson.has("chat-update")  // and the update json contains a "chat-update" object
					&& subscribeEvent.updateJson.getAsJsonObject("chat-update").has("message");


	/* TODOs
	 * TODO add tests for subscription canceling
	 * TODO test manually canceling eventsource / deleting channel
	 */


	@BeforeAll
	public static void setup() throws MalformedURLException {
		int port = 8080;
		URL url = new URL("http://localhost:" + port);
		String shipName = "zod";
		String code = "lidlut-tabwed-pillex-ridrup";

		urbit = new Urbit(url, shipName, code);
		graphStoreAgent = new GraphStoreAgent(urbit);

		subscribeToMailboxEvents = new ArrayList<>();
		primaryChatSubscriptionEvents = new ArrayList<>();

		// Assumes fake ship zod is booted and running
		// Assumes chat channel called 'test' is created

	}

	@Test
	@Order(1)
	public void successfulAuthentication() throws ExecutionException, InterruptedException {
		CompletableFuture<String> futureResponseString = new CompletableFuture<>();
		assertDoesNotThrow(() -> {
			InMemoryResponseWrapper res = urbit.authenticate();
			futureResponseString.complete(res.getBody().utf8());
		});
		await().until(futureResponseString::isDone);
		assertEquals("", futureResponseString.get());
	}

	@Test
	@Order(2)
	public void successfullyConnectToShip() {
		await().until(urbit::isAuthenticated);
		assertDoesNotThrow(() -> urbit.connect());
	}


	@Test
	@Order(3)
	public void canSubscribeToTestChat() throws Exception {
		await().until(urbit::isConnected);

		int subscriptionID = urbit.subscribe(urbit.getShipName(), "chat-store", "/mailbox/~zod/test", subscribeEvent -> subscribeToMailboxEvents.add(subscribeEvent));

		await().until(() -> subscribeToMailboxEvents.size() >= 2);
		assertEquals(SubscribeEvent.EventType.STARTED, subscribeToMailboxEvents.get(0).eventType);
		// todo add assertion for the second event
	}


	@Test
	@Order(4)
	public void canSendChatMessage() throws Exception {
		await().until(urbit::isConnected);
		await().until(() -> !subscribeToMailboxEvents.isEmpty());

		Map<String, Object> payload = Map.of(
				"message", Map.of(
						"path", "/~zod/test",
						"envelope", Map.of(
								"uid", Urbit.uid(),
								"number", 1,
								"author", "~zod",
								"when", Instant.now().toEpochMilli(),
								"letter", Map.of("text", "Hello, Mars! It is now " + Instant.now().toString())
						)
				)
		);

		futureChatPokeResponse1 = urbit.poke(urbit.getShipName(), "chat-hook", "json", AirlockUtils.gson.toJsonTree(payload));
		await().until(futureChatPokeResponse1::isDone);

		assertTrue(futureChatPokeResponse1.get().success);
	}

	@Test
	@Order(5)
	public void testChatView() throws Exception {
		await().until(urbit::isConnected);
		await().until(futureChatPokeResponse1::isDone);


		int subscriptionID = urbit.subscribe(urbit.getShipName(), "chat-view", "/primary", subscribeEvent -> primaryChatSubscriptionEvents.add(subscribeEvent));

		// send a message to a chat that we haven't subscribed to already
		// todo re implement above behavior. it will fail on ci because integration test setup does not create it


		// the specification of this payload is at lib/chat-store.hoon#L119...

		JsonElement json = AirlockUtils.gson.toJsonTree(ChatUtils.createMessagePayload("/~zod/test", "~zod", primaryChatViewTestMessage));
		CompletableFuture<PokeResponse> pokeFuture = urbit.poke(urbit.getShipName(), "chat-hook", "json", json);
		await().until(pokeFuture::isDone);
		assertTrue(pokeFuture.get().success);

		// wait until we have at least one proper "chat-update" message that isn't just the initial 20 messages sent
		await().until(
				() -> primaryChatSubscriptionEvents
						.stream()
						.anyMatch(onlyPrimaryChatUpdate)
		);
		primaryChatSubscriptionEvents.stream().
				filter(onlyPrimaryChatUpdate)
				.findFirst()
				.ifPresentOrElse(subscribeEvent -> {
					ChatUpdate chatUpdate = AirlockUtils.gson.fromJson(subscribeEvent.updateJson.get("chat-update"), ChatUpdate.class);
					System.out.println("Got chat update");
					System.out.println(chatUpdate);
					Objects.requireNonNull(chatUpdate.message);
					assertEquals(primaryChatViewTestMessage, chatUpdate.message.envelope.letter.text);
					futurePrimaryChatMessage.complete(chatUpdate.message.envelope.letter.text);
				}, () -> fail("Chat message received was not the same as the one sent"));

	}

	@Test
	@Order(6)
	public void canScry() throws Exception {
		await().until(urbit::isConnected);
		JsonElement responseJson = urbit.scryRequest("file-server", "/clay/base/hash");
		assertEquals(responseJson.getAsInt(), 0);
	}

	@Test
	@Order(7)
	public void getGraphDataFromScry() throws Exception {
		await().until(urbit::isConnected);
		JsonObject keyScry = urbit.scryRequest("graph-store", "/keys").getAsJsonObject();
		JsonObject tagScry = urbit.scryRequest("graph-store", "/tags").getAsJsonObject();
		JsonObject tagQueriesScry = urbit.scryRequest("graph-store", "/tag-queries").getAsJsonObject();

		System.out.println(keyScry);
		System.out.println("graph scry: /keys response");
		assertTrue(keyScry.has("graph-update"));
		assertTrue(keyScry.get("graph-update").getAsJsonObject().has("keys"));

		System.out.println("graph scry: /tags response");
		System.out.println(tagScry);
		assertTrue(tagScry.has("graph-update"));
		assertTrue(tagScry.get("graph-update").getAsJsonObject().has("tags"));


		System.out.println("graph scry: /tag-queries response");
		System.out.println(tagQueriesScry);
		assertTrue(tagQueriesScry.has("graph-update"));
		assertTrue(tagQueriesScry.get("graph-update").getAsJsonObject().has("tag-queries"));
	}


	@Test
	@Order(8)
	public void canSpider() throws Exception {
		await().until(urbit::isConnected);

		// todo add basic spider test.
		// it used to be graph store but graph store works and so does spider
		// but we need a basic test with a single spider request
		// in order to discern whether or not a possible regression is
		// b/c spider is failing or graph store agent is failing

	}


	@Test
	@Order(9)
	public void createGraph() throws Exception {
		await().until(urbit::isConnected);

		long NOW = Instant.now().toEpochMilli();
		String graphName = "test-graph" + NOW;
		String graphTitle = "Test Graph Created " + NOW;
		String graphDescription = "graph for testing only! having fun strictly prohibited";
		Resource associatedGroup = new Resource("~zod", "TEST_GROUP_" + NOW);
		JsonElement responseJson = graphStoreAgent.createManagedGraph(
				graphName,
				graphTitle,
				graphDescription,
				associatedGroup,
				GraphStoreAgent.Modules.LINK
		);

		assertTrue(responseJson.isJsonNull()); // a successful call gives null

		JsonObject keysPayload = graphStoreAgent.getKeys();
		JsonArray keys = keysPayload.get("graph-update").getAsJsonObject().get("keys").getAsJsonArray();
		JsonObject expectedKey = map2json(Map.of(
				"ship", "zod",
				"name", graphName
		));
		// we expect to see a key with our ship and the name of the graph that we just created
		assertTrue(keys.contains(expectedKey));


	}


}
