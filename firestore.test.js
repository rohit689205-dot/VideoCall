const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read rooms or users", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("rooms").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
});

test("Authenticated user: can create and read their own user profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
});

test("Authenticated user: cannot update someone else's user profile", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).set({
      userId: BOB_UID,
      displayName: "Imposter",
      email: "bob@example.com",
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Room lifecycle: host can create room, attendees can join as participants", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  // Alice creates room
  await assertSucceeds(
    aliceDb.collection("rooms").doc("vid-101").set({
      id: "vid-101",
      hostId: ALICE_UID,
      hostName: "Alice",
      title: "Design Sync",
      status: "active",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob can read room
  await assertSucceeds(bobDb.collection("rooms").doc("vid-101").get());

  // Bob joins as participant
  await assertSucceeds(
    bobDb.collection("rooms").doc("vid-101").collection("participants").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      isAudioMuted: false,
      isVideoOff: false,
      isHost: false,
      joinedAt: now,
      lastSeen: now,
    })
  );

  // Bob sends chat message
  await assertSucceeds(
    bobDb.collection("rooms").doc("vid-101").collection("messages").doc("msg-1").set({
      id: "msg-1",
      roomId: "vid-101",
      senderId: BOB_UID,
      senderName: "Bob",
      text: "Hello everyone!",
      timestamp: now,
    })
  );

  // Alice can read messages
  await assertSucceeds(
    aliceDb.collection("rooms").doc("vid-101").collection("messages").doc("msg-1").get()
  );
});
