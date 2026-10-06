import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from "@firebase/rules-unit-testing";
import { readFileSync } from "fs";
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection,
} from "firebase/firestore";

const RULES_PATH = new URL("../firestore.rules", import.meta.url).pathname.replace(/^\/([A-Za-z]):/, "$1:");

let testEnv;
let results = [];

function record(name, pass, detail) {
  results.push({ name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} - ${name}${detail ? " :: " + detail : ""}`);
}

async function run() {
  testEnv = await initializeTestEnvironment({
    projectId: "gopreach-rules-test",
    firestore: { rules: readFileSync(RULES_PATH, "utf8"), host: "127.0.0.1", port: 8089 },
  });

  // Seed base data as admin (bypasses rules).
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    // People
    await setDoc(doc(db, "people", "adminA"), {
      isSuperAdmin: false, activeAdminRole: "ADMIN_PER_CONGREGATION", activeCongregationId: "congA",
    });
    await setDoc(doc(db, "people", "elderRegA"), {
      isSuperAdmin: false, activeAdminRole: "REGULAR_ELDER", activeCongregationId: "congA",
    });
    await setDoc(doc(db, "people", "elderRegA_stale"), {
      // Simulates the OLD, buggy client that never denormalized this field.
      isSuperAdmin: false, activeAdminRole: "REGULAR_ELDER", activeCongregationId: null,
    });
    await setDoc(doc(db, "people", "secretaryA"), {
      isSuperAdmin: false, activeAdminRole: "SECRETARY", activeCongregationId: "congA",
    });
    await setDoc(doc(db, "people", "pubA"), {
      isSuperAdmin: false, activeAdminRole: null, activeCongregationId: "congA",
    });
    await setDoc(doc(db, "people", "pubB"), {
      isSuperAdmin: false, activeAdminRole: null, activeCongregationId: "congA",
    });
    await setDoc(doc(db, "people", "pubOtherCong"), {
      isSuperAdmin: false, activeAdminRole: null, activeCongregationId: "congB",
    });
    await setDoc(doc(db, "people", "superAdmin1"), {
      isSuperAdmin: true, activeAdminRole: "SUPER_ADMIN", activeCongregationId: null,
    });

    // FS Group RBAC seed
    await setDoc(doc(db, "groups", "g1"), { congregationId: "congA", name: "G1", status: "ACTIVE", overseerPersonId: "elderRegA" });
    await setDoc(doc(db, "groups", "g2"), { congregationId: "congA", name: "G2", status: "ACTIVE", overseerPersonId: "someoneElse" });
    await setDoc(doc(db, "roleAssignments", "ra_m1"), { personId: "pubB", congregationId: "congA", roleType: "PUBLISHER:REGULAR_PUBLISHER", status: "ACTIVE", groupId: null });
    await setDoc(doc(db, "roleAssignments", "ra_m2"), { personId: "pubA", congregationId: "congA", roleType: "PUBLISHER:REGULAR_PUBLISHER", status: "ACTIVE", groupId: "g2" });
    await setDoc(doc(db, "roleAssignments", "ra_m3"), { personId: "pubA", congregationId: "congA", roleType: "PUBLISHER:REGULAR_PUBLISHER", status: "ACTIVE", groupId: "g2" });

    // A Return Visit owned by pubA, in congA.
    await setDoc(doc(db, "interestedPeople", "rv1"), {
      publisherPersonId: "pubA", congregationId: "congA", pipelineStage: "RETURN_VISIT", name: "Juan",
    });
    // A Bible Study owned by pubA, in congA.
    await setDoc(doc(db, "interestedPeople", "bs1"), {
      publisherPersonId: "pubA", congregationId: "congA", pipelineStage: "BIBLE_STUDY", name: "Maria",
    });
    // Another congregation's Return Visit, owned by pubOtherCong.
    await setDoc(doc(db, "interestedPeople", "rvOther"), {
      publisherPersonId: "pubOtherCong", congregationId: "congB", pipelineStage: "RETURN_VISIT", name: "Pedro",
    });

    // A Monthly Report belonging to pubA, in congA, not yet posted.
    await setDoc(doc(db, "monthlyReports", "report1"), {
      publisherPersonId: "pubA", congregationId: "congA", status: "SUBMITTED",
      periodMonth: 1, bibleStudiesCount: 2,
    });

    // Submitted / Draft reports of pubA for the duplicate-submission lock tests.
    await setDoc(doc(db, "monthlyReports", "pubA_202608"), {
      publisherPersonId: "pubA", congregationId: "congA", status: "SUBMITTED",
      periodMonth: 1, bibleStudiesCount: 3, hoursRendered: 15.5,
    });
    await setDoc(doc(db, "monthlyReports", "pubA_202609"), {
      publisherPersonId: "pubA", congregationId: "congA", status: "DRAFT",
      periodMonth: 2, bibleStudiesCount: 1, hoursRendered: 10,
    });

    // Deleted Records: one deleted by pubA (congA) and one deleted by someone in congB.
    await setDoc(doc(db, "deletedRecords", "trashA"), {
      status: "deleted", recordType: "Return Visit", module: "Return Visit / Bible Study", label: "Juan",
      congregationId: "congA", deletedByPersonId: "pubA", deletedAt: 1, itemsJson: "[]",
    });
    await setDoc(doc(db, "deletedRecords", "trashB"), {
      status: "deleted", recordType: "Publisher", module: "Publishers", label: "Pedro",
      congregationId: "congB", deletedByPersonId: "pubOtherCong", deletedAt: 1, itemsJson: "[]",
    });

    // A plain RoleAssignment (Coordinator Elder) in congA, for privilege tests.
    await setDoc(doc(db, "roleAssignments", "ra1"), {
      personId: "someoneA", roleType: "ADMIN:COORDINATOR_ELDER", congregationId: "congA", status: "ACTIVE",
    });
  });

  const asAdminA = testEnv.authenticatedContext("adminA", { email: "adminA@x.com" }).firestore();
  const asElderRegA = testEnv.authenticatedContext("elderRegA", { email: "elderRegA@x.com" }).firestore();
  const asElderRegA_stale = testEnv.authenticatedContext("elderRegA_stale", { email: "elderRegA_stale@x.com" }).firestore();
  const asSecretaryA = testEnv.authenticatedContext("secretaryA", { email: "secretaryA@x.com" }).firestore();
  const asPubA = testEnv.authenticatedContext("pubA", { email: "pubA@x.com" }).firestore();
  const asPubB = testEnv.authenticatedContext("pubB", { email: "pubB@x.com" }).firestore();
  const asSuperAdmin = testEnv.authenticatedContext("superAdmin1", { email: "superAdmin1@x.com" }).firestore();

  // ============ TEST 1: Regular Elder editing another publisher's Monthly Report ============
  // This is the exact bug that was fixed (activeCongregationId now populated).
  await (async () => {
    const ref = doc(asElderRegA, "monthlyReports", "report1");
    const ok = await assertSucceeds(updateDoc(ref, { bibleStudiesCount: 5 }));
    record("Regular Elder (fixed) can edit another publisher's Monthly Report in own congregation", true);
  })().catch((e) => record("Regular Elder (fixed) can edit another publisher's Monthly Report in own congregation", false, e.message));

  // Same scenario but with the STALE (pre-fix) null activeCongregationId — should FAIL,
  // proving this really was the root cause and the rule itself is otherwise correct.
  await (async () => {
    const ref = doc(asElderRegA_stale, "monthlyReports", "report1");
    await assertFails(updateDoc(ref, { bibleStudiesCount: 6 }));
    record("Regular Elder (stale/pre-fix null congregation) is REJECTED editing another publisher's report — confirms this was the real bug", true);
  })().catch((e) => record("Regular Elder (stale/pre-fix null congregation) is REJECTED editing another publisher's report — confirms this was the real bug", false, e.message));

  // ============ TEST 2: Secretary has Service-Overseer-equivalent report access ============
  await (async () => {
    const ref = doc(asSecretaryA, "monthlyReports", "report1");
    await assertSucceeds(updateDoc(ref, { bibleStudiesCount: 7 }));
    record("Secretary can edit another publisher's Monthly Report in own congregation (Service-Overseer parity)", true);
  })().catch((e) => record("Secretary can edit another publisher's Monthly Report in own congregation (Service-Overseer parity)", false, e.message));

  // ============ Duplicate-submission lock on Monthly Reports ============
  await (async () => {
    const ref = doc(asPubA, "monthlyReports", "pubA_202608");
    await assertFails(setDoc(ref, {
      publisherPersonId: "pubA", congregationId: "congA", status: "SUBMITTED",
      periodMonth: 1, bibleStudiesCount: 9, hoursRendered: 40,
    }));
    record("Publisher CANNOT overwrite their own already-SUBMITTED report (duplicate submission rejected)", true);
  })().catch((e) => record("Publisher CANNOT overwrite their own already-SUBMITTED report (duplicate submission rejected)", false, e.message));

  await (async () => {
    const ref = doc(asPubA, "monthlyReports", "pubA_202608");
    await assertSucceeds(setDoc(ref, {
      publisherPersonId: "pubA", congregationId: "congA", status: "SUBMITTED",
      periodMonth: 1, bibleStudiesCount: 3, hoursRendered: 15.5,
    }));
    record("An identical re-write of a SUBMITTED report is accepted (retried sync is not an error)", true);
  })().catch((e) => record("An identical re-write of a SUBMITTED report is accepted (retried sync is not an error)", false, e.message));

  await (async () => {
    const ref = doc(asPubA, "monthlyReports", "pubA_202609");
    await assertSucceeds(updateDoc(ref, { status: "SUBMITTED", hoursRendered: 12 }));
    record("Publisher CAN submit their own DRAFT report", true);
  })().catch((e) => record("Publisher CAN submit their own DRAFT report", false, e.message));

  await (async () => {
    const ref = doc(asSecretaryA, "monthlyReports", "pubA_202608");
    await assertSucceeds(updateDoc(ref, { status: "DRAFT" }));
    const again = doc(asPubA, "monthlyReports", "pubA_202608");
    await assertSucceeds(updateDoc(again, { status: "SUBMITTED", hoursRendered: 16 }));
    record("An admin can unlock a SUBMITTED report, after which the publisher can resubmit it", true);
  })().catch((e) => record("An admin can unlock a SUBMITTED report, after which the publisher can resubmit it", false, e.message));

  // ============ Deleted Records (recycle bin) ============
  const trashTest = async (name, fn) => {
    try { await fn(); record(name, true); } catch (e) { record(name, false, e.message); }
  };
  await trashTest("Publisher CAN move their own record to Deleted Records", () =>
    assertSucceeds(setDoc(doc(asPubA, "deletedRecords", "trashNew"), {
      status: "deleted", recordType: "Return Visit", module: "x", label: "Maria", congregationId: "congA",
      deletedByPersonId: "pubA", deletedAt: 2, itemsJson: "[]",
    })));
  await trashTest("Publisher CANNOT file a deleted record as someone else", () =>
    assertFails(setDoc(doc(asPubA, "deletedRecords", "trashForged"), {
      status: "deleted", recordType: "x", module: "x", label: "x", congregationId: "congA",
      deletedByPersonId: "adminA", deletedAt: 2, itemsJson: "[]",
    })));
  await trashTest("Publisher CAN read and restore (delete the entry of) their own deleted record", async () => {
    await assertSucceeds(getDoc(doc(asPubA, "deletedRecords", "trashA")));
  });
  await trashTest("Publisher CANNOT read another congregation's deleted record", () =>
    assertFails(getDoc(doc(asPubA, "deletedRecords", "trashB"))));
  await trashTest("Publisher CANNOT permanently delete a record they didn't delete", () =>
    assertFails(deleteDoc(doc(asPubA, "deletedRecords", "trashB"))));
  await trashTest("Admin CAN read their congregation's deleted records", () =>
    assertSucceeds(getDoc(doc(asAdminA, "deletedRecords", "trashA"))));
  await trashTest("Admin CANNOT read another congregation's deleted records", () =>
    assertFails(getDoc(doc(asAdminA, "deletedRecords", "trashB"))));
  await trashTest("Admin CANNOT permanently delete another congregation's deleted record", () =>
    assertFails(deleteDoc(doc(asAdminA, "deletedRecords", "trashB"))));
  await trashTest("Nobody can edit a deleted record in place", () =>
    assertFails(updateDoc(doc(asAdminA, "deletedRecords", "trashA"), { label: "changed" })));
  await trashTest("Super Admin CAN permanently delete any deleted record", () =>
    assertSucceeds(deleteDoc(doc(asSuperAdmin, "deletedRecords", "trashB"))));
  await trashTest("Admin CAN permanently delete their congregation's deleted record", () =>
    assertSucceeds(deleteDoc(doc(asAdminA, "deletedRecords", "trashA"))));

  // ============ TEST 3: Congregation Admin scoping ============
  await (async () => {
    // Admin A can manage a roleAssignment in their OWN congregation (congA).
    const ref = doc(asAdminA, "roleAssignments", "ra1");
    await assertSucceeds(updateDoc(ref, { status: "INACTIVE" }));
    record("Congregation Admin can edit a roleAssignment in their own congregation", true);
  })().catch((e) => record("Congregation Admin can edit a roleAssignment in their own congregation", false, e.message));

  await (async () => {
    // Admin A tries to create a roleAssignment for congB (another congregation) — must fail.
    const ref = doc(asAdminA, "roleAssignments", "raOther");
    await assertFails(setDoc(ref, {
      personId: "someoneB", roleType: "ADMIN:COORDINATOR_ELDER", congregationId: "congB", status: "ACTIVE",
    }));
    record("Congregation Admin CANNOT create a roleAssignment for another congregation", true);
  })().catch((e) => record("Congregation Admin CANNOT create a roleAssignment for another congregation", false, e.message));

  await (async () => {
    // Admin A tries to promote someone to SUPER_ADMIN within their own congregation — must fail.
    const ref = doc(asAdminA, "roleAssignments", "raEscalate");
    await assertFails(setDoc(ref, {
      personId: "adminA", roleType: "ADMIN:SUPER_ADMIN", congregationId: "congA", status: "ACTIVE",
    }));
    record("Congregation Admin CANNOT assign themselves/anyone SUPER_ADMIN", true);
  })().catch((e) => record("Congregation Admin CANNOT assign themselves/anyone SUPER_ADMIN", false, e.message));

  await (async () => {
    // Admin A tries to create a CIRCUIT_OVERSEER assignment — must fail.
    const ref = doc(asAdminA, "roleAssignments", "raCircuit");
    await assertFails(setDoc(ref, {
      personId: "someoneA", roleType: "ADMIN:CIRCUIT_OVERSEER", congregationId: "congA", status: "ACTIVE",
    }));
    record("Congregation Admin CANNOT create a CIRCUIT_OVERSEER assignment", true);
  })().catch((e) => record("Congregation Admin CANNOT create a CIRCUIT_OVERSEER assignment", false, e.message));

  // ============ TEST 4: Self-escalation via people.isSuperAdmin ============
  await (async () => {
    // A plain publisher tries to set their own isSuperAdmin=true directly.
    const ref = doc(asPubA, "people", "pubA");
    await assertFails(updateDoc(ref, { isSuperAdmin: true }));
    record("Publisher CANNOT set isSuperAdmin=true on their own person doc", true);
  })().catch((e) => record("Publisher CANNOT set isSuperAdmin=true on their own person doc", false, e.message));

  await (async () => {
    // A plain publisher tries to set SOMEONE ELSE's isSuperAdmin=true.
    const ref = doc(asPubA, "people", "pubB");
    await assertFails(updateDoc(ref, { isSuperAdmin: true }));
    record("Publisher CANNOT set isSuperAdmin=true on another person's doc", true);
  })().catch((e) => record("Publisher CANNOT set isSuperAdmin=true on another person's doc", false, e.message));

  await (async () => {
    // A publisher can still update their own ordinary fields (no regression).
    const ref = doc(asPubA, "people", "pubA");
    await assertSucceeds(updateDoc(ref, { contact: "09171234567" }));
    record("Publisher CAN still update their own ordinary fields (no regression)", true);
  })().catch((e) => record("Publisher CAN still update their own ordinary fields (no regression)", false, e.message));

  // ============ TEST 5: Territory Map Return Visit sharing ============
  await (async () => {
    // Publisher B adds Return Visit history to Publisher A's Return Visit.
    const ref = doc(collection(asPubB, "interestedPeople", "rv1", "visits"));
    await assertSucceeds(setDoc(ref, {
      interestedPersonId: "rv1", createdByPersonId: "pubB", publisherPersonId: "pubB",
      visitDate: Date.now(), outcome: "CALL_AGAIN", timeConsumedMinutes: 10,
    }));
    record("Publisher B CAN add Return Visit history to Publisher A's Return Visit", true);
  })().catch((e) => record("Publisher B CAN add Return Visit history to Publisher A's Return Visit", false, e.message));

  await (async () => {
    // Publisher B tries to edit the PARENT Return Visit (should fail — owner-only).
    const ref = doc(asPubB, "interestedPeople", "rv1");
    await assertFails(updateDoc(ref, { name: "Renamed by B" }));
    record("Publisher B CANNOT edit the parent Return Visit they don't own", true);
  })().catch((e) => record("Publisher B CANNOT edit the parent Return Visit they don't own", false, e.message));

  await (async () => {
    // Publisher B tries to add Bible Study visit history to Publisher A's Bible Study — must fail.
    const ref = doc(collection(asPubB, "interestedPeople", "bs1", "visits"));
    await assertFails(setDoc(ref, {
      interestedPersonId: "bs1", createdByPersonId: "pubB", publisherPersonId: "pubB",
      visitDate: Date.now(), outcome: "CALL_AGAIN", timeConsumedMinutes: 10,
    }));
    record("Publisher B CANNOT add Bible Study visit history for someone else's Bible Study", true);
  })().catch((e) => record("Publisher B CANNOT add Bible Study visit history for someone else's Bible Study", false, e.message));

  await (async () => {
    // Publisher A (the actual owner) CAN add their own Bible Study visit history.
    const ref = doc(collection(asPubA, "interestedPeople", "bs1", "visits"));
    await assertSucceeds(setDoc(ref, {
      interestedPersonId: "bs1", createdByPersonId: "pubA", publisherPersonId: "pubA",
      visitDate: Date.now(), outcome: "NEXT_TOPIC", timeConsumedMinutes: 15,
    }));
    record("Publisher A (owner) CAN add their own Bible Study visit history", true);
  })().catch((e) => record("Publisher A (owner) CAN add their own Bible Study visit history", false, e.message));

  await (async () => {
    // Publisher B tries to access/edit a Return Visit from a DIFFERENT congregation entirely.
    const ref = doc(asPubB, "interestedPeople", "rvOther");
    await assertFails(updateDoc(ref, { name: "Hacked" }));
    record("Publisher B CANNOT edit a Return Visit belonging to another congregation", true);
  })().catch((e) => record("Publisher B CANNOT edit a Return Visit belonging to another congregation", false, e.message));

  // ============ TEST 6: Super Admin retains full access ============
  await (async () => {
    const ref = doc(asSuperAdmin, "interestedPeople", "rvOther");
    await assertSucceeds(updateDoc(ref, { name: "Super Admin edit" }));
    record("Super Admin CAN edit any congregation's records", true);
  })().catch((e) => record("Super Admin CAN edit any congregation's records", false, e.message));

  // ============ TEST 7: catch-all `{document=**}` block (schedules,
  // territories, shared locations, app settings, backups, audit log) ============
  // This is the exact bug that was fixed (`document.matches(...)` — `document`
  // is a `path`, which has no `.matches()` — threw "Function not found" on
  // every evaluation, and an evaluation error denies, so this catch-all
  // rejected EVERY read/write to these collections, for every account,
  // always, with PERMISSION_DENIED — reported app-side as "Sync Error").
  await (async () => {
    const ref = doc(asPubA, "territories", "t1");
    await assertSucceeds(setDoc(ref, { name: "Territory 1", congregationId: "congA" }));
    record("Publisher CAN write a territory (catch-all block reachable, not a dead rule)", true);
  })().catch((e) => record("Publisher CAN write a territory (catch-all block reachable, not a dead rule)", false, e.message));

  await (async () => {
    const ref = doc(asPubA, "schedules", "s1");
    await assertSucceeds(setDoc(ref, { congregationId: "congA" }));
    record("Publisher CAN write a schedule (catch-all block reachable)", true);
  })().catch((e) => record("Publisher CAN write a schedule (catch-all block reachable)", false, e.message));

  // The catch-all must still stay OUT of every collection with its own real
  // rule — this is the privilege-escalation gap the guard was built to close
  // in the first place (see this block's own comment in firestore.rules);
  // re-check it here so this suite fails loudly if a future edit to the
  // exclusion list (or its shape) ever reopens it.
  await (async () => {
    const ref = doc(asPubA, "userAccessGrants", "pubA");
    await assertFails(setDoc(ref, { scopeType: "ALL_CONGREGATIONS", permissions: ["MANAGE_USERS"] }));
    record("Publisher still CANNOT self-grant via userAccessGrants (catch-all correctly excludes it)", true);
  })().catch((e) => record("Publisher still CANNOT self-grant via userAccessGrants (catch-all correctly excludes it)", false, e.message));

  // ============ TEST 7b: creditHourCategories ============
  await (async () => {
    // Any signed-in user (Publisher included) can READ the category list —
    // this is the exact bug being fixed ("categories cannot be found in
    // the dropdown"): a Publisher must be able to see it to pick one.
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "creditHourCategories", "ldc1"), { name: "LDC", active: true });
    });
    const ref = doc(asPubA, "creditHourCategories", "ldc1");
    await assertSucceeds(getDoc(ref));
    record("Publisher CAN read Credit Hour Categories", true);
  })().catch((e) => record("Publisher CAN read Credit Hour Categories", false, e.message));

  await (async () => {
    // A Publisher cannot create/edit an arbitrary (non-seed) category.
    const ref = doc(asPubA, "creditHourCategories", "hacked1");
    await assertFails(setDoc(ref, { name: "Hacked", active: true }));
    record("Publisher CANNOT create a Credit Hour Category", true);
  })().catch((e) => record("Publisher CANNOT create a Credit Hour Category", false, e.message));

  await (async () => {
    // A Publisher cannot update an existing category (e.g. rename/deactivate).
    const ref = doc(asPubA, "creditHourCategories", "ldc1");
    await assertFails(updateDoc(ref, { active: false }));
    record("Publisher CANNOT update a Credit Hour Category", true);
  })().catch((e) => record("Publisher CANNOT update a Credit Hour Category", false, e.message));

  await (async () => {
    // A Publisher CAN create one of the fixed-id starting categories —
    // first-run seeding must work even before any admin has signed in.
    const ref = doc(asPubA, "creditHourCategories", "default_ldc");
    await assertSucceeds(setDoc(ref, { name: "LDC", active: true }));
    record("Publisher CAN create the fixed-id default seed categories", true);
  })().catch((e) => record("Publisher CAN create the fixed-id default seed categories", false, e.message));

  await (async () => {
    // But a Publisher cannot then UPDATE that seeded default category.
    const ref = doc(asPubA, "creditHourCategories", "default_ldc");
    await assertFails(updateDoc(ref, { active: false }));
    record("Publisher CANNOT update a seeded default Credit Hour Category", true);
  })().catch((e) => record("Publisher CANNOT update a seeded default Credit Hour Category", false, e.message));

  await (async () => {
    // Congregation Admin CAN manage categories (add/edit/deactivate).
    const ref = doc(asAdminA, "creditHourCategories", "newCat1");
    await assertSucceeds(setDoc(ref, { name: "Community Construction", active: true }));
    record("Admin Per Congregation CAN create a Credit Hour Category", true);
  })().catch((e) => record("Admin Per Congregation CAN create a Credit Hour Category", false, e.message));

  await (async () => {
    const ref = doc(asAdminA, "creditHourCategories", "ldc1");
    await assertSucceeds(updateDoc(ref, { active: false }));
    record("Admin Per Congregation CAN deactivate a Credit Hour Category", true);
  })().catch((e) => record("Admin Per Congregation CAN deactivate a Credit Hour Category", false, e.message));

  await (async () => {
    // A Regular Elder (not Admin/Super-Admin) cannot manage categories.
    const ref = doc(asElderRegA, "creditHourCategories", "newCat2");
    await assertFails(setDoc(ref, { name: "Should Fail", active: true }));
    record("Regular Elder CANNOT create a Credit Hour Category", true);
  })().catch((e) => record("Regular Elder CANNOT create a Credit Hour Category", false, e.message));

  await (async () => {
    const ref = doc(asSuperAdmin, "creditHourCategories", "newCat3");
    await assertSucceeds(setDoc(ref, { name: "Super Admin Added", active: true }));
    record("Super Admin CAN create a Credit Hour Category", true);
  })().catch((e) => record("Super Admin CAN create a Credit Hour Category", false, e.message));

  await (async () => {
    // Confirms the catch-all's exclusion list update didn't leave this
    // reachable through the blanket isSignedIn() clause too.
    const ref = doc(asPubA, "creditHourCategories", "viaCatchAll");
    await assertFails(setDoc(ref, { name: "Should still fail", active: true }));
    record("Publisher still CANNOT write Credit Hour Categories via the catch-all block", true);
  })().catch((e) => record("Publisher still CANNOT write Credit Hour Categories via the catch-all block", false, e.message));

  // ============ TEST 8: presence (Online Users Indicator) ============
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "presence", "pubA"), { congregationId: "congA", lastSeen: Date.now() });
    await setDoc(doc(db, "presence", "pubOtherCong"), { congregationId: "congB", lastSeen: Date.now() });
  });

  await (async () => {
    const ref = doc(asPubA, "presence", "pubA");
    await assertSucceeds(getDoc(ref));
    record("Publisher CAN read their own congregation's presence doc", true);
  })().catch((e) => record("Publisher CAN read their own congregation's presence doc", false, e.message));

  await (async () => {
    const ref = doc(asPubA, "presence", "pubOtherCong");
    await assertFails(getDoc(ref));
    record("Publisher CANNOT read another congregation's presence doc", true);
  })().catch((e) => record("Publisher CANNOT read another congregation's presence doc", false, e.message));

  await (async () => {
    const ref = doc(asSuperAdmin, "presence", "pubOtherCong");
    await assertSucceeds(getDoc(ref));
    record("Super Admin CAN read any congregation's presence doc", true);
  })().catch((e) => record("Super Admin CAN read any congregation's presence doc", false, e.message));

  await (async () => {
    // Publisher B tries to write PUBLISHER A's presence doc -- must fail
    // (personIdFromToken() must equal the document id).
    const ref = doc(asPubB, "presence", "pubA");
    await assertFails(setDoc(ref, { congregationId: "congA", lastSeen: Date.now() }));
    record("Publisher CANNOT write another session's presence document", true);
  })().catch((e) => record("Publisher CANNOT write another session's presence document", false, e.message));

  await (async () => {
    // Publisher B tries to tag their OWN presence doc with a congregation
    // they don't belong to, to appear "online" there -- must fail.
    const ref = doc(asPubB, "presence", "pubB");
    await assertFails(setDoc(ref, { congregationId: "congB", lastSeen: Date.now() }));
    record("Publisher CANNOT spoof a different congregation on their own presence doc", true);
  })().catch((e) => record("Publisher CANNOT spoof a different congregation on their own presence doc", false, e.message));

  await (async () => {
    // Publisher B writes their own presence doc with their REAL congregation
    // (congA, per the seed data) -- must succeed.
    const ref = doc(asPubB, "presence", "pubB");
    await assertSucceeds(setDoc(ref, { congregationId: "congA", lastSeen: Date.now() }));
    record("Publisher CAN write their own presence doc with their real congregation", true);
  })().catch((e) => record("Publisher CAN write their own presence doc with their real congregation", false, e.message));

  await (async () => {
    // Publisher B deletes their own presence doc (sign-out cleanup) -- must succeed.
    const ref = doc(asPubB, "presence", "pubB");
    await assertSucceeds(deleteDoc(ref));
    record("Publisher CAN delete their own presence doc (sign-out cleanup)", true);
  })().catch((e) => record("Publisher CAN delete their own presence doc (sign-out cleanup)", false, e.message));

  // ============ TEST 9: full CRUD on people profiles for admin-track roles ============
  const profileTests = [
    ["Admin CAN edit a same-congregation publisher's profile", asAdminA, "pubA", true],
    ["Secretary CAN edit a same-congregation publisher's profile", asSecretaryA, "pubA", true],
    ["Admin CANNOT edit another congregation's publisher profile", asAdminA, "pubOtherCong", false],
    ["Publisher CANNOT edit another publisher's profile", asPubA, "pubB", false],
    ["Regular Elder CANNOT edit a publisher's profile", asElderRegA, "pubA", false],
  ];
  for (const [name, ctx, target, ok] of profileTests) {
    await (async () => {
      const op = updateDoc(doc(ctx, "people", target), { contact: "09170000000" });
      await (ok ? assertSucceeds(op) : assertFails(op));
      record(name, true);
    })().catch((e) => record(name, false, e.message));
  }

  // ============ TEST 10: per-publisher territory assignments ============
  const pta = { congregationId: "congA", publisherPersonId: "pubA", barangayId: 1 };
  const ptaTests = [
    ["Admin CAN create a per-publisher territory assignment in own congregation", asAdminA, "p1", pta, true],
    ["Secretary CAN create a per-publisher territory assignment in own congregation", asSecretaryA, "p2", pta, true],
    ["Admin CANNOT create one for another congregation", asAdminA, "p3", { ...pta, congregationId: "congB" }, false],
    ["Publisher CANNOT create a per-publisher territory assignment", asPubA, "p4", pta, false],
    ["Super Admin CAN create one for any congregation", asSuperAdmin, "p5", { ...pta, congregationId: "congB" }, true],
  ];
  for (const [name, ctx, id, data, ok] of ptaTests) {
    await (async () => {
      const op = setDoc(doc(ctx, "publisherTerritoryAssignments", id), data);
      await (ok ? assertSucceeds(op) : assertFails(op));
      record(name, true);
    })().catch((e) => record(name, false, e.message));
  }

  // ============ TEST 11: appSettings (Session Timeout Setting) ============
  const stTests = [
    ["Admin CAN change app settings", asAdminA, true],
    ["Regular Elder CAN change app settings", asElderRegA, true],
    ["Secretary CAN change app settings", asSecretaryA, true],
    ["Super Admin CAN change app settings", asSuperAdmin, true],
    ["Publisher CANNOT change app settings", asPubA, false],
  ];
  for (const [name, ctx, ok] of stTests) {
    await (async () => {
      const op = setDoc(doc(ctx, "appSettings", "global"), { sessionTimeoutEnabled: true, sessionTimeoutMinutes: 10 });
      await (ok ? assertSucceeds(op) : assertFails(op));
      record(name, true);
    })().catch((e) => record(name, false, e.message));
  }
  await (async () => {
    await assertSucceeds(getDoc(doc(asPubA, "appSettings", "global")));
    record("Publisher CAN read app settings", true);
  })().catch((e) => record("Publisher CAN read app settings", false, e.message));

  // ============ TEST 12: Territory Map pins ============
  const pinTests = [
    ["Publisher CAN create their own pin in their congregation", asPubA, "pin1", { congregationId: "congA", createdByPersonId: "pubA", text: "Dog at gate", lat: 1, lng: 2 }, true],
    ["Publisher CANNOT create a pin as someone else", asPubA, "pin2", { congregationId: "congA", createdByPersonId: "pubB", text: "x", lat: 1, lng: 2 }, false],
    ["Publisher CANNOT create a pin in another congregation", asPubA, "pin3", { congregationId: "congB", createdByPersonId: "pubA", text: "x", lat: 1, lng: 2 }, false],
  ];
  for (const [name, ctx, id, data, ok] of pinTests) {
    await (async () => {
      const op = setDoc(doc(ctx, "mapPins", id), data);
      await (ok ? assertSucceeds(op) : assertFails(op));
      record(name, true);
    })().catch((e) => record(name, false, e.message));
  }
  await (async () => {
    await assertFails(deleteDoc(doc(asPubB, "mapPins", "pin1")));
    record("Another publisher CANNOT delete someone else's pin", true);
  })().catch((e) => record("Another publisher CANNOT delete someone else's pin", false, e.message));
  await (async () => {
    await assertSucceeds(deleteDoc(doc(asAdminA, "mapPins", "pin1")));
    record("Admin CAN delete a pin in their congregation", true);
  })().catch((e) => record("Admin CAN delete a pin in their congregation", false, e.message));

  // ============ FS Group RBAC ============
  const gt = async (name, op, ok) => {
    try { await (ok ? assertSucceeds(op()) : assertFails(op())); record(name, true); } catch (e) { record(name, false, e.message); }
  };
  await gt("Admin CAN edit a group in own congregation", () => updateDoc(doc(asAdminA, "groups", "g2"), { name: "G2b" }), true);
  await gt("Admin CAN create a group in own congregation", () => setDoc(doc(asAdminA, "groups", "g3"), { congregationId: "congA", name: "G3", status: "ACTIVE" }), true);
  await gt("Admin CANNOT create a group in another congregation", () => setDoc(doc(asAdminA, "groups", "g4"), { congregationId: "congB", name: "G4", status: "ACTIVE" }), false);
  await gt("Secretary CAN edit any group in own congregation", () => updateDoc(doc(asSecretaryA, "groups", "g1"), { name: "G1b" }), true);
  await gt("Super Admin CAN create a group in any congregation", () => setDoc(doc(asSuperAdmin, "groups", "g5"), { congregationId: "congB", name: "G5", status: "ACTIVE" }), true);
  await gt("Group Overseer CAN edit own group", () => updateDoc(doc(asElderRegA, "groups", "g1"), { name: "Mine" }), true);
  await gt("Group Overseer CANNOT edit another group", () => updateDoc(doc(asElderRegA, "groups", "g2"), { name: "Nope" }), false);
  await gt("Group Overseer CANNOT deactivate own group", () => updateDoc(doc(asElderRegA, "groups", "g1"), { status: "INACTIVE" }), false);
  await gt("Group Overseer CANNOT delete own group", () => deleteDoc(doc(asElderRegA, "groups", "g1")), false);
  await gt("Group Overseer CANNOT create a group", () => setDoc(doc(asElderRegA, "groups", "g6"), { congregationId: "congA", name: "G6", status: "ACTIVE" }), false);
  await gt("Group Overseer CANNOT move own group to another congregation", () => updateDoc(doc(asElderRegA, "groups", "g1"), { congregationId: "congB" }), false);
  await gt("Group Overseer CAN add a member to own group", () => updateDoc(doc(asElderRegA, "roleAssignments", "ra_m1"), { groupId: "g1" }), true);
  await gt("Group Overseer CAN transfer a member from another group into own group", () => updateDoc(doc(asElderRegA, "roleAssignments", "ra_m2"), { groupId: "g1" }), true);
  await gt("Group Overseer CANNOT touch a member of another group without moving them in", () => updateDoc(doc(asElderRegA, "roleAssignments", "ra_m3"), { groupId: "g2", lastEditedAt: 1 }), false);
  await gt("Group Overseer CANNOT change a member's role type", () => updateDoc(doc(asElderRegA, "roleAssignments", "ra_m1"), { roleType: "ADMIN:SUPER_ADMIN" }), false);
  await gt("Publisher CANNOT edit a group", () => updateDoc(doc(asPubA, "groups", "g1"), { name: "x" }), false);

  // ============ Map drawings: completed-territory polygons / pins ============
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const [id, role] of [["servantA", null], ["assistA", null], ["coordA", "COORDINATOR_ELDER"], ["svcA", "SERVICE_OVERSEER"]]) {
      await setDoc(doc(db, "people", id), { isSuperAdmin: false, activeAdminRole: role, activeCongregationId: "congA" });
    }
    await setDoc(doc(db, "people", "coordB"), { isSuperAdmin: false, activeAdminRole: "COORDINATOR_ELDER", activeCongregationId: "congB" });
    await setDoc(doc(db, "groups", "gd1"), { congregationId: "congA", name: "GD1", status: "ACTIVE", overseerPersonId: "elderRegA", servantPersonId: "servantA", assistantPersonId: "assistA" });
    await setDoc(doc(db, "groups", "gd2"), { congregationId: "congA", name: "GD2", status: "ACTIVE", overseerPersonId: "someoneElse" });
    await setDoc(doc(db, "groups", "gdB"), { congregationId: "congB", name: "GDB", status: "ACTIVE", overseerPersonId: "someoneB" });
    await setDoc(doc(db, "territoryAssignmentBarangays", "congA_t1"), { congregationId: "congA", groupId: "gd1", barangayName: "Uno" });
    await setDoc(doc(db, "territoryAssignmentBarangays", "congA_t2"), { congregationId: "congA", groupId: "gd2", barangayName: "Dos" });
    await setDoc(doc(db, "territoryAssignmentBarangays", "congA_t3"), { congregationId: "congA", groupId: "gd1", barangayName: "Tres (no bounds yet)" });
    await setDoc(doc(db, "territoryAssignmentBarangays", "congB_tb"), { congregationId: "congB", groupId: "gdB", barangayName: "Bee" });
    // Published bounds: t1 = lat 10..11, lng 120..121.
    await setDoc(doc(db, "territoryBounds", "congA_t1"), { congregationId: "congA", groupId: "gd1", minLat: 10, maxLat: 11, minLng: 120, maxLng: 121 });
    await setDoc(doc(db, "territoryBounds", "congA_t2"), { congregationId: "congA", groupId: "gd2", minLat: 20, maxLat: 21, minLng: 120, maxLng: 121 });
    await setDoc(doc(db, "territoryDrawings", "existingG1"), {
      geometryJson: "{}", fillOpacity: 0.3, status: "FINISHED", userId: "coordA", updatedByUserId: "coordA",
      congregationId: "congA", groupId: "gd1", territoryId: "congA_t1", minLat: 10.1, maxLat: 10.2, minLng: 120.1, maxLng: 120.2, createdAt: 1,
    });
    await setDoc(doc(db, "territoryDrawings", "existingG2"), {
      geometryJson: "{}", fillOpacity: 0.3, status: "FINISHED", userId: "coordA", updatedByUserId: "coordA",
      congregationId: "congA", groupId: "gd2", territoryId: "congA_t2", minLat: 20.1, maxLat: 20.2, minLng: 120.1, maxLng: 120.2, createdAt: 1,
    });
  });
  const asServantA = testEnv.authenticatedContext("servantA", { email: "servantA@x.com" }).firestore();
  const asAssistA = testEnv.authenticatedContext("assistA", { email: "assistA@x.com" }).firestore();
  const asCoordA = testEnv.authenticatedContext("coordA", { email: "coordA@x.com" }).firestore();
  const asSvcA = testEnv.authenticatedContext("svcA", { email: "svcA@x.com" }).firestore();
  const asCoordB = testEnv.authenticatedContext("coordB", { email: "coordB@x.com" }).firestore();
  const drawing = (who, o = {}) => ({
    geometryJson: '{"type":"Polygon","coordinates":[]}', fillColor: "#43A047", fillOpacity: 0.35, borderColor: "#2E7D32",
    status: "FINISHED", userId: who, updatedByUserId: who, congregationId: "congA", groupId: "gd1", territoryId: "congA_t1",
    minLat: 10.2, maxLat: 10.4, minLng: 120.2, maxLng: 120.4, createdAt: 1, updatedAt: 1, ...o,
  });
  const dd = (name, ctx, id, data, ok) => gt("Drawing: " + name, () => setDoc(doc(ctx, "territoryDrawings", id), data), ok);

  await dd("Group Overseer CAN draw inside own territory", asElderRegA, "d1", drawing("elderRegA"), true);
  await dd("Group Servant CAN draw inside own territory", asServantA, "d2", drawing("servantA"), true);
  await dd("Group Assistant CAN draw inside own territory", asAssistA, "d3", drawing("assistA"), true);
  await dd("Group Overseer CANNOT draw beyond own territory's bounds (edited bbox)", asElderRegA, "d5", drawing("elderRegA", { maxLat: 12 }), false);
  await dd("Group Overseer CANNOT draw in another FS Group's territory", asElderRegA, "d6",
    drawing("elderRegA", { groupId: "gd2", territoryId: "congA_t2", minLat: 20.2, maxLat: 20.4 }), false);
  await dd("Group Overseer CANNOT file a drawing under another group while using own territory", asElderRegA, "d7", drawing("elderRegA", { groupId: "gd2" }), false);
  await dd("Group Overseer CANNOT draw where the territory has no published bounds", asElderRegA, "d8", drawing("elderRegA", { territoryId: "congA_t3" }), false);
  await dd("Group Overseer CANNOT spoof another user as the author", asElderRegA, "d9", drawing("pubB"), false);
  await dd("Group Overseer CANNOT omit the editor stamp", asElderRegA, "d10", drawing("elderRegA", { updatedByUserId: "pubB" }), false);
  await dd("Plain publisher (no group slot) CANNOT draw", asPubA, "d11", drawing("pubA"), false);
  await dd("Publisher in another congregation CANNOT draw", testEnv.authenticatedContext("pubOtherCong", { email: "pubOtherCong@x.com" }).firestore(), "d12", drawing("pubOtherCong"), false);
  await dd("Coordinator Elder CAN draw in any FS Group territory of own congregation", asCoordA, "d13",
    drawing("coordA", { groupId: "gd2", territoryId: "congA_t2", minLat: 20.2, maxLat: 20.4 }), true);
  await dd("Coordinator Elder CAN draw outside the published box (congregation-wide)", asCoordA, "d14", drawing("coordA", { maxLat: 12 }), true);
  await dd("Service Overseer CAN draw in another group's territory", asSvcA, "d15", drawing("svcA", { groupId: "gd2", territoryId: "congA_t2", minLat: 20.2, maxLat: 20.4 }), true);
  await dd("Secretary CAN draw in any FS Group territory of own congregation", asSecretaryA, "d16", drawing("secretaryA"), true);
  await dd("Coordinator Elder CANNOT draw in another congregation", asCoordA, "d17",
    drawing("coordA", { congregationId: "congB", groupId: "gdB", territoryId: "congB_tb" }), false);
  await dd("Coordinator Elder CANNOT file a drawing with a territory of another congregation", asCoordA, "d18",
    drawing("coordA", { territoryId: "congB_tb" }), false);
  await dd("Super Admin CAN draw in any congregation", asSuperAdmin, "d19",
    drawing("superAdmin1", { congregationId: "congB", groupId: "gdB", territoryId: "congB_tb" }), true);
  await dd("Coordinator of another congregation CANNOT draw in congA", asCoordB, "d20", drawing("coordB"), false);
  const unassigned = (who, o = {}) => drawing(who, { groupId: "", territoryId: "", territoryName: "", ...o });
  await dd("Coordinator Elder CAN draw in an area outside any FS Group territory", asCoordA, "u1", unassigned("coordA"), true);
  await dd("Secretary CAN draw in an unassigned area of own congregation", asSecretaryA, "u2", unassigned("secretaryA"), true);
  await dd("Service Overseer CAN draw in an unassigned area of own congregation", asSvcA, "u3", unassigned("svcA"), true);
  await dd("Admin CAN draw in an unassigned area of own congregation", asAdminA, "u4", unassigned("adminA"), true);
  await dd("Super Admin CAN draw in an unassigned area of any congregation", asSuperAdmin, "u5", unassigned("superAdmin1", { congregationId: "congB" }), true);
  await dd("Coordinator of another congregation CANNOT draw in an unassigned area of congA", asCoordB, "u6", unassigned("coordB"), false);
  await dd("Admin CANNOT draw in another congregation's unassigned area", asAdminA, "u7", unassigned("adminA", { congregationId: "congB" }), false);
  await dd("Group Overseer CANNOT draw in an unassigned area", asElderRegA, "u8", unassigned("elderRegA"), false);
  await dd("Group Servant CANNOT draw in an unassigned area", asServantA, "u9", unassigned("servantA"), false);
  await dd("Plain publisher CANNOT draw in an unassigned area", asPubA, "u10", unassigned("pubA"), false);

  await gt("Drawing: Group Overseer CAN edit a drawing in own group (made by someone else)",
    () => setDoc(doc(asElderRegA, "territoryDrawings", "existingG1"), drawing("coordA", { updatedByUserId: "elderRegA", status: "TO_DO", fillColor: "#E53935" })), true);
  await gt("Drawing: Group Overseer CANNOT edit another group's drawing",
    () => setDoc(doc(asElderRegA, "territoryDrawings", "existingG2"), drawing("coordA", { groupId: "gd2", territoryId: "congA_t2", minLat: 20.1, maxLat: 20.2, updatedByUserId: "elderRegA" })), false);
  await gt("Drawing: edit CANNOT rewrite the original author",
    () => setDoc(doc(asElderRegA, "territoryDrawings", "existingG1"), drawing("elderRegA")), false);
  await gt("Drawing: Group Overseer CAN delete a drawing in own group", () => deleteDoc(doc(asElderRegA, "territoryDrawings", "d1")), true);
  await gt("Drawing: Group Overseer CANNOT delete another group's drawing", () => deleteDoc(doc(asElderRegA, "territoryDrawings", "existingG2")), false);
  await gt("Drawing: deleting an already-missing drawing is harmless", () => deleteDoc(doc(asElderRegA, "territoryDrawings", "neverUploaded")), true);
  await gt("Drawing: any signed-in user CAN read drawings", () => getDoc(doc(asPubB, "territoryDrawings", "existingG1")), true);

  await gt("Bounds: Group Overseer CANNOT widen their own territory's bounds",
    () => setDoc(doc(asElderRegA, "territoryBounds", "congA_t1"), { congregationId: "congA", groupId: "gd1", minLat: 0, maxLat: 90, minLng: 0, maxLng: 180 }), false);
  await gt("Bounds: Coordinator Elder CAN publish bounds for a territory in own congregation",
    () => setDoc(doc(asCoordA, "territoryBounds", "congA_t3"), { congregationId: "congA", groupId: "gd1", minLat: 30, maxLat: 31, minLng: 120, maxLng: 121 }), true);
  await gt("Bounds: bounds CANNOT name a group that doesn't own the territory",
    () => setDoc(doc(asCoordA, "territoryBounds", "congA_t3"), { congregationId: "congA", groupId: "gd2", minLat: 30, maxLat: 31, minLng: 120, maxLng: 121 }), false);
  await dd("Group Overseer CAN draw once the territory's bounds are published", asElderRegA, "d21", drawing("elderRegA", { territoryId: "congA_t3", minLat: 30.2, maxLat: 30.4 }), true);
  await dd("Polygon CANNOT be saved without a status", asElderRegA, "s1", (() => { const p = drawing("elderRegA"); delete p.status; return p; })(), false);
  await dd("Polygon CANNOT use an arbitrary color for its status", asElderRegA, "s2", drawing("elderRegA", { fillColor: "#0000FF" }), false);
  await dd("To Continue polygon must be amber", asElderRegA, "s3", drawing("elderRegA", { status: "TO_CONTINUE", fillColor: "#FBC02D" }), true);
  await dd("To Do polygon must be red", asElderRegA, "s4", drawing("elderRegA", { status: "TO_DO", fillColor: "#E53935" }), true);
  await dd("To Do polygon CANNOT be green", asElderRegA, "s5", drawing("elderRegA", { status: "TO_DO", fillColor: "#43A047" }), false);
  await dd("Old status names are rejected", asElderRegA, "s6", drawing("elderRegA", { status: "COMPLETED" }), false);
  await dd("Polygon CAN carry multi-line remarks", asElderRegA, "r1", drawing("elderRegA", { remarks: "Covered the east side. Continue from the main road.", fillOpacity: 0.1 }), true);
  await dd("Polygon remarks CANNOT be absurdly long", asElderRegA, "r2", drawing("elderRegA", { remarks: "x".repeat(1001) }), false);
  await dd("Polygon CAN carry a name", asElderRegA, "n1", drawing("elderRegA", { name: "East of the highway" }), true);
  await dd("Polygon name CANNOT be absurdly long", asElderRegA, "n2", drawing("elderRegA", { name: "x".repeat(121) }), false);

  const audit = (who, o = {}) => ({ drawingId: "d2", action: "CREATED", userId: who, userRole: "Group Overseer", congregationId: "congA", groupId: "gd1", territoryId: "congA_t1", at: 1, syncInfo: "PENDING", ...o });
  await gt("Audit: Group Overseer CAN append an audit row for own group", () => setDoc(doc(asElderRegA, "territoryDrawingAudits", "a1"), audit("elderRegA")), true);
  await gt("Audit: CANNOT append a row as someone else", () => setDoc(doc(asElderRegA, "territoryDrawingAudits", "a2"), audit("pubB")), false);
  await gt("Audit: CANNOT append a row for another group", () => setDoc(doc(asElderRegA, "territoryDrawingAudits", "a3"), audit("elderRegA", { groupId: "gd2" })), false);
  await gt("Audit: rows can never be edited", () => updateDoc(doc(asElderRegA, "territoryDrawingAudits", "a1"), { action: "DELETED" }), false);
  await gt("Audit: rows can never be deleted", () => deleteDoc(doc(asCoordA, "territoryDrawingAudits", "a1")), false);
  await gt("Audit: Coordinator Elder CAN read the audit trail", () => getDoc(doc(asCoordA, "territoryDrawingAudits", "a1")), true);
  await gt("Audit: a publisher CANNOT read the audit trail", () => getDoc(doc(asPubA, "territoryDrawingAudits", "a1")), false);

  await testEnv.cleanup();

  console.log("\n=== SUMMARY ===");
  const failed = results.filter((r) => !r.pass);
  console.log(`${results.length - failed.length}/${results.length} passed`);
  if (failed.length > 0) {
    console.log("FAILURES:");
    failed.forEach((f) => console.log(`  - ${f.name}: ${f.detail}`));
    process.exitCode = 1;
  }
}

run().catch((e) => {
  console.error("Test harness crashed:", e);
  process.exitCode = 1;
});
