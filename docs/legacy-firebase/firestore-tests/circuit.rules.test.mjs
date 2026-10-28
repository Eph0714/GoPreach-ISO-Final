// Circuit Overseer module — security-rule tests (run with `npm run test:circuit`).
// Covers: only the Super-Admin can write Circuit Codes / congregation links, everyone signed-in can read them,
// and a Circuit Overseer's grant confines the reports/records they can actually read to their own congregations.
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { readFileSync } from "fs";
import { fileURLToPath } from "url";
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, query, where, documentId, writeBatch,
} from "firebase/firestore";

const RULES_PATH = fileURLToPath(new URL("../firestore.rules", import.meta.url));
const results = [];
function record(name, pass, detail) {
  results.push({ name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} - ${name}${detail ? " :: " + detail : ""}`);
}
const t = async (name, op, ok) => {
  try { await (ok ? assertSucceeds(op()) : assertFails(op())); record(name, true); } catch (e) { record(name, false, e.message); }
};

async function run() {
  const testEnv = await initializeTestEnvironment({
    projectId: "gopreach-rules-test",
    firestore: { rules: readFileSync(RULES_PATH, "utf8"), host: "127.0.0.1", port: 8089 },
  });

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "people", "superAdmin1"), { isSuperAdmin: true, activeAdminRole: "SUPER_ADMIN", activeCongregationId: null });
    await setDoc(doc(db, "people", "adminA"), { isSuperAdmin: false, activeAdminRole: "ADMIN_PER_CONGREGATION", activeCongregationId: "solano" });
    await setDoc(doc(db, "people", "pubA"), { isSuperAdmin: false, activeAdminRole: null, activeCongregationId: "solano" });
    await setDoc(doc(db, "people", "coA"), { isSuperAdmin: false, activeAdminRole: "CIRCUIT_OVERSEER", activeCongregationId: null });
    await setDoc(doc(db, "people", "coB"), { isSuperAdmin: false, activeAdminRole: "CIRCUIT_OVERSEER", activeCongregationId: null });
    for (const [id, name] of [["solano", "Solano"], ["bayombong", "Bayombong"], ["bambang", "Bambang"]]) {
      await setDoc(doc(db, "congregations", id), { name, status: "ACTIVE" });
      await setDoc(doc(db, "monthlyReports", `r_${id}`), { publisherPersonId: `p_${id}`, congregationId: id, status: "SUBMITTED", periodMonth: 1 });
    }
    const perms = ["VIEW_CONGREGATIONS", "VIEW_GROUPS", "VIEW_ELDERS", "VIEW_PUBLISHERS", "VIEW_PUBLISHER_REPORTS", "VIEW_GROUP_REPORTS", "VIEW_CONGREGATION_REPORTS", "PRINT_REPORTS", "EXPORT_REPORTS"];
    await setDoc(doc(db, "userAccessGrants", "coA"), { personId: "coA", permissions: perms, scopeType: "SELECTED_CONGREGATIONS", scopeCongregationIds: ["solano", "bayombong"], circuitCode: "NT01" });
    await setDoc(doc(db, "userAccessGrants", "coB"), { personId: "coB", permissions: perms, scopeType: "SELECTED_CONGREGATIONS", scopeCongregationIds: ["bambang"], circuitCode: "NT02" });
    await setDoc(doc(db, "circuitCodes", "NT01"), { code: "NT01", status: "ACTIVE", overseerPersonId: "coA" });
    await setDoc(doc(db, "congregationCircuits", "solano"), { congregationId: "solano", circuitOverseerPersonId: "coA", circuitCode: "NT01" });
  });

  const as = (id) => testEnv.authenticatedContext(id, { email: `${id}@x.com` }).firestore();
  const su = as("superAdmin1"), admin = as("adminA"), pub = as("pubA"), coA = as("coA"), coB = as("coB");

  // ---- Circuit Codes ----
  await t("Super-Admin CAN create a Circuit Code", () => setDoc(doc(su, "circuitCodes", "NT03"), { code: "NT03", status: "ACTIVE", overseerPersonId: null }), true);
  await t("Super-Admin CAN update a Circuit Code", () => updateDoc(doc(su, "circuitCodes", "NT03"), { description: "x" }), true);
  await t("Super-Admin CAN delete a Circuit Code", () => deleteDoc(doc(su, "circuitCodes", "NT03")), true);
  await t("Admin CANNOT create a Circuit Code", () => setDoc(doc(admin, "circuitCodes", "NT09"), { code: "NT09", status: "ACTIVE" }), false);
  await t("Publisher CANNOT create a Circuit Code", () => setDoc(doc(pub, "circuitCodes", "NT09"), { code: "NT09", status: "ACTIVE" }), false);
  await t("Admin CANNOT update a Circuit Code", () => updateDoc(doc(admin, "circuitCodes", "NT01"), { overseerPersonId: "adminA" }), false);
  await t("Publisher CANNOT take over a Circuit Code", () => updateDoc(doc(pub, "circuitCodes", "NT01"), { overseerPersonId: "pubA" }), false);
  await t("Circuit Overseer CANNOT edit a Circuit Code", () => updateDoc(doc(coB, "circuitCodes", "NT01"), { overseerPersonId: "coB" }), false);
  await t("Circuit Overseer CANNOT delete a Circuit Code", () => deleteDoc(doc(coA, "circuitCodes", "NT01")), false);
  await t("Signed-in user CAN read Circuit Codes", () => getDoc(doc(pub, "circuitCodes", "NT01")), true);
  await t("Signed-out user CANNOT read Circuit Codes", () => getDoc(doc(testEnv.unauthenticatedContext().firestore(), "circuitCodes", "NT01")), false);

  // ---- Congregation -> Circuit Overseer links ----
  await t("Super-Admin CAN create a congregation link", () => setDoc(doc(su, "congregationCircuits", "bayombong"), { congregationId: "bayombong", circuitOverseerPersonId: "coA", circuitCode: "NT01" }), true);
  await t("Admin CANNOT create a congregation link", () => setDoc(doc(admin, "congregationCircuits", "bambang"), { congregationId: "bambang", circuitOverseerPersonId: "adminA", circuitCode: "NT01" }), false);
  await t("Circuit Overseer B CANNOT steal A's congregation", () => setDoc(doc(coB, "congregationCircuits", "solano"), { congregationId: "solano", circuitOverseerPersonId: "coB", circuitCode: "NT02" }), false);
  await t("Circuit Overseer B CANNOT update A's congregation link", () => updateDoc(doc(coB, "congregationCircuits", "solano"), { circuitOverseerPersonId: "coB" }), false);
  await t("Publisher CANNOT delete a congregation link", () => deleteDoc(doc(pub, "congregationCircuits", "solano")), false);
  await t("Signed-in user CAN read congregation links", () => getDoc(doc(pub, "congregationCircuits", "solano")), true);

  // ---- Grants can't be self-edited ----
  await t("Circuit Overseer CANNOT widen their own scope", () => updateDoc(doc(coB, "userAccessGrants", "coB"), { scopeCongregationIds: ["bambang", "solano"] }), false);
  await t("Publisher CANNOT create a grant for themselves", () => setDoc(doc(pub, "userAccessGrants", "pubA"), { personId: "pubA", permissions: ["VIEW_CONGREGATION_REPORTS"], scopeType: "ALL_CONGREGATIONS" }), false);

  // ---- A Circuit Overseer only reaches their own congregations' data ----
  await t("CO A CANNOT read the live Field Service Record of an assigned congregation (solano)", () => getDoc(doc(coA, "monthlyReports", "r_solano")), false);
  await t("CO A CANNOT read the live Field Service Record of an assigned congregation (bayombong)", () => getDoc(doc(coA, "monthlyReports", "r_bayombong")), false);
  await t("CO A CANNOT read a report of another circuit's congregation (bambang)", () => getDoc(doc(coA, "monthlyReports", "r_bambang")), false);
  await t("CO B CANNOT read CO A's congregation report", () => getDoc(doc(coB, "monthlyReports", "r_solano")), false);
  await t("CO B CANNOT read the live Field Service Record of its own congregation", () => getDoc(doc(coB, "monthlyReports", "r_bambang")), false);
  await t("CO A CAN read an assigned congregation record", () => getDoc(doc(coA, "congregations", "solano")), true);
  await t("CO A CANNOT read another circuit's congregation record", () => getDoc(doc(coA, "congregations", "bambang")), false);
  await t("CO A CANNOT write a report", () => updateDoc(doc(coA, "monthlyReports", "r_solano"), { bibleStudiesCount: 99 }), false);

  // ---- List queries a Circuit Overseer's device would run ----
  const reports = (db) => collection(db, "monthlyReports");
  await t("QUERY: CO A live reports where congregationId == solano is refused", () => getDocs(query(reports(coA), where("congregationId", "==", "solano"))), false);
  await t("QUERY: CO A live reports for its own congregations is refused", () => getDocs(query(reports(coA), where("congregationId", "in", ["solano", "bayombong"]))), false);
  await t("QUERY: CO A reports where congregationId in [solano, bambang] is refused", () => getDocs(query(reports(coA), where("congregationId", "in", ["solano", "bambang"]))), false);
  await t("QUERY: CO A reports where congregationId == bambang is refused", () => getDocs(query(reports(coA), where("congregationId", "==", "bambang"))), false);
  await t("QUERY: CO A unfiltered reports list is refused", () => getDocs(reports(coA)), false);
  await t("QUERY: CO A congregations by id in [solano, bayombong]", () => getDocs(query(collection(coA, "congregations"), where(documentId(), "in", ["solano", "bayombong"]))), true);
  await t("QUERY: CO A congregations unfiltered is refused", () => getDocs(collection(coA, "congregations")), false);
  await t("QUERY: CO A grants unfiltered is refused", () => getDocs(collection(coA, "userAccessGrants")), false);
  await t("QUERY: CO A grants where documentId == own id", () => getDocs(query(collection(coA, "userAccessGrants"), where(documentId(), "==", "coA"))), true);
  await t("QUERY: CO A grants by personId field is refused (must query by document id)", () => getDocs(query(collection(coA, "userAccessGrants"), where("personId", "==", "coA"))), false);
  await t("QUERY: CO A roleAssignments where congregationId in assigned", () => getDocs(query(collection(coA, "roleAssignments"), where("congregationId", "in", ["solano", "bayombong"]))), true);
  await t("QUERY: CO A groups where congregationId in assigned", () => getDocs(query(collection(coA, "groups"), where("congregationId", "in", ["solano", "bayombong"]))), true);
  await t("QUERY: CO A own roleAssignments where personId == me", () => getDocs(query(collection(coA, "roleAssignments"), where("personId", "==", "coA"))), true);


  // ---- Territory data: a Circuit Overseer reads only their congregations, and never writes ----
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    for (const c of ["solano", "bambang"]) {
      await setDoc(doc(db, "territoryAssignmentBarangays", `${c}_1`), { congregationId: c, groupId: "g", barangayId: 1 });
      await setDoc(doc(db, "territoryAssignments", `ta_${c}`), { congregationId: c, groupId: "g" });
      await setDoc(doc(db, "territoryBounds", `${c}_1`), { congregationId: c, groupId: "g", minLat: 1, maxLat: 2, minLng: 1, maxLng: 2 });
      await setDoc(doc(db, "territoryDrawings", `d_${c}`), { congregationId: c, groupId: "g", userId: "x", status: "ACTIVE" });
      await setDoc(doc(db, "mapPins", `p_${c}`), { congregationId: c, createdByPersonId: "x", text: "t" });
    }
  });
  for (const col of ["territoryAssignmentBarangays", "territoryAssignments", "territoryBounds", "territoryDrawings", "mapPins"]) {
    const id = (c) => ({ territoryAssignmentBarangays: `${c}_1`, territoryAssignments: `ta_${c}`, territoryBounds: `${c}_1`, territoryDrawings: `d_${c}`, mapPins: `p_${c}` })[col];
    await t(`TERRITORY ${col}: CO A CAN read an assigned congregation's document`, () => getDoc(doc(coA, col, id("solano"))), true);
    await t(`TERRITORY ${col}: CO A CANNOT read another circuit's document`, () => getDoc(doc(coA, col, id("bambang"))), false);
    await t(`TERRITORY ${col}: CO A CAN list its congregations only (filtered)`, () => getDocs(query(collection(coA, col), where("congregationId", "in", ["solano", "bayombong"]))), true);
    await t(`TERRITORY ${col}: CO A CANNOT list it unfiltered`, () => getDocs(collection(coA, col)), false);
    await t(`TERRITORY ${col}: a publisher/admin (no grant) still reads it`, () => getDoc(doc(admin, col, id("bambang"))), true);
    await t(`TERRITORY ${col}: CO A CANNOT edit it`, () => updateDoc(doc(coA, col, id("solano")), { note: "x" }), false);
    await t(`TERRITORY ${col}: CO A CANNOT delete it`, () => deleteDoc(doc(coA, col, id("solano"))), false);
  }
  await t("TERRITORY: CO A CANNOT create a drawing", () => setDoc(doc(coA, "territoryDrawings", "dNew"), { congregationId: "solano", groupId: "g", userId: "coA", status: "ACTIVE" }), false);
  await t("TERRITORY: CO A CANNOT create a pin", () => setDoc(doc(coA, "mapPins", "pNew"), { congregationId: "solano", createdByPersonId: "coA", text: "t" }), false);
  await t("TERRITORY: CO A CANNOT claim a barangay", () => setDoc(doc(coA, "territoryAssignmentBarangays", "solano_9"), { congregationId: "solano", groupId: "g", barangayId: 9 }), false);

  // ---- Field Service Report workflow on the ACTUAL report (status per congregation and month) ----
  // No copies: the Circuit Overseer reads the congregation's own monthlyReports, only for months that were submitted.
  const MONTH = 1000; // far in the past, so "not in the future"
  const FUTURE = 4102444800000; // year 2100
  const REAL = 1788192000000; // 1 Sep 2026 00:00 (UTC+8): a real service-month start, for the date-based locks
  const DAY = 86400000;
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "people", "secA"), { isSuperAdmin: false, activeAdminRole: "SECRETARY", activeCongregationId: "solano" });
    await setDoc(doc(db, "people", "ceA"), { isSuperAdmin: false, activeAdminRole: "COORDINATOR_ELDER", activeCongregationId: "solano" });
    await setDoc(doc(db, "people", "soA"), { isSuperAdmin: false, activeAdminRole: "SERVICE_OVERSEER", activeCongregationId: "solano" });
    await setDoc(doc(db, "people", "elderA"), { isSuperAdmin: false, activeAdminRole: "REGULAR_ELDER", activeCongregationId: "solano" });
    await setDoc(doc(db, "people", "secB"), { isSuperAdmin: false, activeAdminRole: "SECRETARY", activeCongregationId: "bambang" });
    const st = (m, status, extra = {}) => ({ congregationId: "solano", periodMonth: m, status, version: 1, updatedAt: 1, ...extra });
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_1000"), st(1000, "SUBMITTED"));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_2000"), st(2000, "RECEIVED", { coRemarks: "ok" }));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_3000"), st(3000, "RETURNED", { coRemarks: "fix" }));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_4000"), st(4000, "NOT_SUBMITTED"));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_5000"), st(5000, "SUBMITTED"));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "bambang_2000"), { ...st(2000, "SUBMITTED"), congregationId: "bambang" });
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_9000"), st(9000, "SUBMITTED"));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_4500"), st(4500, "NOT_SUBMITTED"));
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_3500"), st(3500, "RETURNED"));
    await setDoc(doc(db, "coFieldServiceMonthStatus", `solano_${REAL}`), st(REAL, "RECEIVED"));
    for (const m of [1000, 2000, 3000, 4000, 4500, 6000, 9000]) {
      await setDoc(doc(db, "monthlyReports", `act_solano_${m}`), { publisherPersonId: "pubA", congregationId: "solano", periodMonth: m, status: "SUBMITTED", bibleStudiesCount: 1 });
    }
  });
  const secA = as("secA"), ceA = as("ceA"), soA = as("soA"), elderA = as("elderA"), secB = as("secB");
  const sdoc = (db, id) => doc(db, "coFieldServiceMonthStatus", id);
  const send = (m, extra = {}) => ({ congregationId: "solano", periodMonth: m, status: "SUBMITTED", version: 1, submittedAt: 1, submittedByPersonId: "x", submittedByName: "X", updatedAt: 1, ...extra });

  // Who can send, and never a future month
  await t("SEND: Admin CAN send a past month", () => setDoc(sdoc(admin, "solano_7000"), send(7000)), true);
  await t("SEND: Coordinator Elder CAN send", () => setDoc(sdoc(ceA, "solano_7001"), send(7001)), true);
  await t("SEND: Service Overseer CAN send", () => setDoc(sdoc(soA, "solano_7002"), send(7002)), true);
  await t("SEND: Secretary CAN send", () => setDoc(sdoc(secA, "solano_7003"), send(7003)), true);
  await t("SEND: Regular Elder CANNOT send", () => setDoc(sdoc(elderA, "solano_7004"), send(7004)), false);
  await t("SEND: Publisher CANNOT send", () => setDoc(sdoc(pub, "solano_7005"), send(7005)), false);
  await t("SEND: another congregation's Secretary CANNOT send", () => setDoc(sdoc(secB, "solano_7006"), send(7006)), false);
  await t("SEND: Circuit Overseer CANNOT send", () => setDoc(sdoc(coA, "solano_7007"), send(7007)), false);
  await t("SEND: a FUTURE service month CANNOT be sent (Admin)", () => setDoc(sdoc(admin, `solano_${FUTURE}`), send(FUTURE)), false);
  await t("SEND: a FUTURE service month CANNOT be sent (Secretary)", () => setDoc(sdoc(secA, `solano_${FUTURE}`), send(FUTURE)), false);
  await t("SEND: the id must be congregation_month", () => setDoc(sdoc(secA, "solano_wrong"), send(7008)), false);
  await t("SEND: cannot create straight into RECEIVED", () => setDoc(sdoc(secA, "solano_7009"), send(7009, { status: "RECEIVED" })), false);
  await t("SEND: a first send carries no CO remarks", () => setDoc(sdoc(secA, "solano_7010"), send(7010, { coRemarks: "hi" })), false);
  await t("SEND: a NOT_SUBMITTED month CAN be sent", () => updateDoc(sdoc(secA, "solano_4000"), { status: "SUBMITTED", submittedAt: 2, submittedByName: "Sec", updatedAt: 2 }), true);
  await t("SEND: a NOT_SUBMITTED month CANNOT be sent as a future month (month cannot be moved)", () => updateDoc(sdoc(secA, "solano_5000"), { periodMonth: FUTURE }), false);
  await t("RESEND: a RETURNED month CAN be sent again", () => updateDoc(sdoc(secA, "solano_3000"), { status: "SUBMITTED", version: 2, submittedAt: 2, submittedByName: "Sec", updatedAt: 2 }), true);
  await t("RESEND: the sender CANNOT forge CO remarks while sending", () => updateDoc(sdoc(secA, "solano_3000"), { status: "SUBMITTED", coRemarks: "forged", updatedAt: 3 }), false);

  // Undo before the CO receives it
  await t("UNDO: Secretary CAN undo a SUBMITTED month (back to NOT_SUBMITTED)", () => updateDoc(sdoc(secA, "solano_1000"), { status: "NOT_SUBMITTED", coRemarks: null, undoneAt: 5, undoneByName: "Sec", updatedAt: 5 }), true);
  await t("UNDO: Admin CAN undo a SUBMITTED month", () => updateDoc(sdoc(admin, "solano_5000"), { status: "NOT_SUBMITTED", coRemarks: null, undoneAt: 5, updatedAt: 5 }), true);
  await t("UNDO: a RECEIVED month CANNOT be undone by the congregation", () => updateDoc(sdoc(admin, "solano_2000"), { status: "NOT_SUBMITTED", coRemarks: null, updatedAt: 5 }), false);
  await t("UNDO: another congregation CANNOT undo", () => updateDoc(sdoc(secB, "solano_7000"), { status: "NOT_SUBMITTED", coRemarks: null, updatedAt: 5 }), false);
  await t("UNDO: a Publisher CANNOT undo", () => updateDoc(sdoc(pub, "solano_7000"), { status: "NOT_SUBMITTED", coRemarks: null, updatedAt: 5 }), false);

  // The Circuit Overseer: receive, return, remarks
  await t("CO: CAN receive a SUBMITTED month with remarks", () => updateDoc(sdoc(coA, "solano_7000"), { status: "RECEIVED", receivedAt: 6, receivedByName: "CO", coRemarks: "Complete", coRemarksAt: 6, updatedAt: 6 }), true);
  await t("CO: CAN update remarks of a RECEIVED month", () => updateDoc(sdoc(coA, "solano_7000"), { coRemarks: "Reviewed", coRemarksAt: 7, updatedAt: 7 }), true);
  await t("CO: CAN return a RECEIVED month", () => updateDoc(sdoc(coA, "solano_7000"), { status: "RETURNED", returnedAt: 8, returnReason: "Check RV", coRemarks: "Check RV", updatedAt: 8 }), true);
  await t("CO: CAN update remarks of a RETURNED month", () => updateDoc(sdoc(coA, "solano_7000"), { coRemarks: "Check RV count", updatedAt: 9 }), true);
  await t("CO: CAN return a SUBMITTED month", () => updateDoc(sdoc(coA, "solano_7001"), { status: "RETURNED", returnReason: "x", coRemarks: "x", updatedAt: 8 }), true);
  await t("CO: CANNOT receive a RETURNED month directly", () => updateDoc(sdoc(coA, "solano_7001"), { status: "RECEIVED", updatedAt: 9 }), false);
  await t("CO: CANNOT touch a NOT_SUBMITTED month's remarks", () => updateDoc(sdoc(coA, "solano_5000"), { coRemarks: "x", updatedAt: 9 }), false);
  await t("CO: CANNOT forge the submission fields", () => updateDoc(sdoc(coA, "solano_7002"), { status: "RECEIVED", submittedByName: "Forged", updatedAt: 9 }), false);
  await t("CO: another circuit's overseer CANNOT receive", () => updateDoc(sdoc(coB, "solano_7002"), { status: "RECEIVED", updatedAt: 9 }), false);
  await t("CO: CANNOT receive outside their congregations", () => updateDoc(sdoc(coA, "bambang_2000"), { status: "RECEIVED", updatedAt: 9 }), false);
  await t("CO: Admin CANNOT receive", () => updateDoc(sdoc(admin, "solano_7002"), { status: "RECEIVED", updatedAt: 9 }), false);
  await t("CO: Secretary CANNOT return a RECEIVED month (only the CO reverts it)", () => updateDoc(sdoc(secA, "solano_7000"), { status: "RETURNED", updatedAt: 9 }), false);
  await t("STATUS: nobody deletes a status document", () => deleteDoc(sdoc(admin, "solano_7002")), false);
  await t("STATUS: any signed-in user reads statuses", () => getDocs(collection(pub, "coFieldServiceMonthStatus")), true);

  // The Circuit Overseer sees the ACTUAL records, only of submitted months
  const coRead = (m) => getDocs(query(collection(coA, "monthlyReports"), where("congregationId", "==", "solano"), where("periodMonth", "==", m)));
  await t("VISIBLE: CO reads the actual records of a SUBMITTED month", () => coRead(9000), true);
  await t("VISIBLE: CO reads the actual records of a RECEIVED month", () => coRead(2000), true);
  await t("VISIBLE: CO reads the actual records of a RETURNED month", () => coRead(3000), true);
  await t("VISIBLE: CO reads a single record of a submitted month", () => getDoc(doc(coA, "monthlyReports", "act_solano_2000")), true);
  await t("HIDDEN: CO CANNOT read a NOT_SUBMITTED month's records", () => getDoc(doc(coA, "monthlyReports", "act_solano_4500")), false);
  await t("HIDDEN: CO CANNOT read a month that has no status at all", () => getDoc(doc(coA, "monthlyReports", "act_solano_6000")), false);
  await t("HIDDEN: CO CANNOT read another circuit's submitted month", () => getDoc(doc(coB, "monthlyReports", "act_solano_2000")), false);
  await t("HIDDEN: CO CANNOT list a month without status", () => getDocs(query(collection(coA, "monthlyReports"), where("congregationId", "==", "solano"), where("periodMonth", "==", 6000))), false);
  await t("HIDDEN: CO CANNOT write the records", () => updateDoc(doc(coA, "monthlyReports", "act_solano_2000"), { bibleStudiesCount: 9 }), false);

  // Locks: SUBMITTED and RECEIVED lock the month for everyone but the Super-Admin; RETURNED / NOT_SUBMITTED open it
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "monthlyReports", "own_2000"), { publisherPersonId: "pubA", congregationId: "solano", periodMonth: 2000, status: "DRAFT" });
    await setDoc(doc(db, "monthlyReports", "own_3500"), { publisherPersonId: "pubA", congregationId: "solano", periodMonth: 3500, status: "DRAFT" });
    await setDoc(doc(db, "monthlyReports", "own_7500"), { publisherPersonId: "pubA", congregationId: "solano", periodMonth: 7500, status: "DRAFT" });
  });
  const mrep = (m, extra = {}) => ({ publisherPersonId: "pubA", congregationId: "solano", periodMonth: m, status: "DRAFT", ...extra });
  await t("LOCK: a publisher CAN write an open month (no status)", () => setDoc(doc(pub, "monthlyReports", "own_8000"), mrep(8000)), true);
  await t("LOCK: a publisher CANNOT write a RECEIVED month", () => updateDoc(doc(pub, "monthlyReports", "own_2000"), { bibleStudiesCount: 3 }), false);
  await t("LOCK: a publisher CANNOT write a SUBMITTED month", () => setDoc(doc(pub, "monthlyReports", "own_7001"), mrep(7002)), false);
  await t("LOCK: a publisher CAN write a RETURNED month", () => updateDoc(doc(pub, "monthlyReports", "own_3500"), { bibleStudiesCount: 3 }), true);
  await t("LOCK: the Secretary CANNOT write a manual record for a RECEIVED month", () => setDoc(doc(secA, "monthlyReports", "man_2000"), mrep(2000, { source: "MANUAL" })), false);
  await t("LOCK: the Secretary CANNOT delete a record of a RECEIVED month", () => deleteDoc(doc(secA, "monthlyReports", "own_2000")), false);
  await t("LOCK: other months stay open", () => setDoc(doc(secA, "monthlyReports", "man_8100"), mrep(8100, { source: "MANUAL" })), true);
  await t("LOCK: the Super-Admin is not bound by the lock", () => updateDoc(doc(su, "monthlyReports", "own_2000"), { bibleStudiesCount: 2 }), true);
  await t("LOCK: hours (preaching time) of a locked month cannot be added", () => setDoc(doc(pub, "preachingTimeRecords", "pt1"), { publisherPersonId: "pubA", congregationId: "solano", date: REAL + 5 * DAY, hoursConsumed: 2 }), false);
  await t("LOCK: hours of an open month can", () => setDoc(doc(pub, "preachingTimeRecords", "pt2"), { publisherPersonId: "pubA", congregationId: "solano", date: REAL + 40 * DAY, hoursConsumed: 2 }), true);
  await t("LOCK: credit hours of a locked month cannot be added", () => setDoc(doc(pub, "creditHourRecords", "ch1"), { publisherPersonId: "pubA", dayStart: REAL + 5 * DAY, hours: 1 }), false);
  await t("LOCK: credit hours of an open month can", () => setDoc(doc(pub, "creditHourRecords", "ch2"), { publisherPersonId: "pubA", dayStart: REAL + 40 * DAY, hours: 1 }), true);
  await t("LOCK: a planner day of a locked month cannot be added", () => setDoc(doc(pub, "plannerDays", "pd1"), { publisherPersonId: "pubA", dayStart: REAL + 5 * DAY, totalMinutes: 30 }), false);
  await t("LOCK: a planner day of an open month can", () => setDoc(doc(pub, "plannerDays", "pd2"), { publisherPersonId: "pubA", dayStart: REAL + 40 * DAY, totalMinutes: 30 }), true);

  // The audit trail
  const ev = (who, o = {}) => ({ reportId: "solano_7000", congregationId: "solano", userId: who, action: "Received", at: 1, ...o });
  await t("AUDIT: Circuit Overseer CAN append an event as themselves", () => setDoc(doc(coA, "coFieldServiceReportEvents", "e1"), ev("coA")), true);
  await t("AUDIT: a sender CAN append an event as themselves", () => setDoc(doc(secA, "coFieldServiceReportEvents", "e2"), ev("secA")), true);
  await t("AUDIT: CANNOT append an event as someone else", () => setDoc(doc(secA, "coFieldServiceReportEvents", "e3"), ev("coA")), false);
  await t("AUDIT: a Publisher CANNOT append events", () => setDoc(doc(pub, "coFieldServiceReportEvents", "e4"), ev("pubA")), false);
  await t("AUDIT: events can never be edited", () => updateDoc(doc(coA, "coFieldServiceReportEvents", "e1"), { action: "x" }), false);
  await t("AUDIT: events can never be deleted", () => deleteDoc(doc(secA, "coFieldServiceReportEvents", "e2")), false);
  await t("AUDIT: the CO of the congregation reads events", () => getDoc(doc(coA, "coFieldServiceReportEvents", "e1")), true);
  await t("AUDIT: a Publisher CANNOT read events", () => getDoc(doc(pub, "coFieldServiceReportEvents", "e1")), false);

  // ---- Active role scope: Group Coordinator / Servant / Assistant manage only their own FS Group ----
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "people", "juanGrp"), { isSuperAdmin: false, activeAdminRole: "REGULAR_ELDER", activeCongregationId: "solano", activeGroupId: "g5" });
    await setDoc(doc(db, "people", "juanCe"), { isSuperAdmin: false, activeAdminRole: "COORDINATOR_ELDER", activeCongregationId: "solano", activeGroupId: null });
    await setDoc(doc(db, "people", "fakeGrp"), { isSuperAdmin: false, activeAdminRole: "REGULAR_ELDER", activeCongregationId: "solano", activeGroupId: "g5" });
    await setDoc(doc(db, "people", "plainElder"), { isSuperAdmin: false, activeAdminRole: "REGULAR_ELDER", activeCongregationId: "solano", activeGroupId: null });
    await setDoc(doc(db, "groups", "g5"), { congregationId: "solano", name: "FS Group 5", overseerPersonId: "juanGrp", servantPersonId: "x1", assistantPersonId: "x2" });
    await setDoc(doc(db, "groups", "g6"), { congregationId: "solano", name: "FS Group 6", overseerPersonId: "someoneElse" });
    await setDoc(doc(db, "roleAssignments", "ra_p5"), { personId: "pubG5", congregationId: "solano", groupId: "g5", status: "ACTIVE", roleType: "PUBLISHER:REGULAR_PUBLISHER" });
    await setDoc(doc(db, "roleAssignments", "ra_p6"), { personId: "pubG6", congregationId: "solano", groupId: "g6", status: "ACTIVE", roleType: "PUBLISHER:REGULAR_PUBLISHER" });
    await setDoc(doc(db, "roleAssignments", "ra_p5_old"), { personId: "pubG5", congregationId: "solano", groupId: "g5", status: "INACTIVE", roleType: "PUBLISHER:REGULAR_PUBLISHER" });
    await setDoc(doc(db, "monthlyReports", "g5_existing"), { publisherPersonId: "pubG5", congregationId: "solano", periodMonth: 9500, status: "SUBMITTED", groupRoleAssignmentId: "ra_p5" });
    await setDoc(doc(db, "monthlyReports", "g5_plain"), { publisherPersonId: "pubG5", congregationId: "solano", periodMonth: 9600, status: "SUBMITTED" });
    await setDoc(doc(db, "monthlyReports", "g6_existing"), { publisherPersonId: "pubG6", congregationId: "solano", periodMonth: 9500, status: "SUBMITTED", groupRoleAssignmentId: "ra_p6" });
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_9700"), { congregationId: "solano", periodMonth: 9700, status: "SUBMITTED", version: 1, updatedAt: 1 });
  });
  const juanGrp = as("juanGrp"), juanCe = as("juanCe"), fakeGrp = as("fakeGrp"), plainElder = as("plainElder");
  const rr = (who, m, ra, extra = {}) => ({ publisherPersonId: who, congregationId: "solano", periodMonth: m, status: "SUBMITTED", source: "MANUAL", groupRoleAssignmentId: ra, ...extra });
  await t("GROUP ROLE: CAN write a record of a publisher of its own FS Group", () => setDoc(doc(juanGrp, "monthlyReports", "g5_new"), rr("pubG5", 9800, "ra_p5")), true);
  await t("GROUP ROLE: CAN edit an existing record of its own group's publisher", () => updateDoc(doc(juanGrp, "monthlyReports", "g5_existing"), { bibleStudiesCount: 2, groupRoleAssignmentId: "ra_p5" }), true);
  await t("GROUP ROLE: CANNOT write a record of another FS Group's publisher", () => setDoc(doc(juanGrp, "monthlyReports", "g6_new"), rr("pubG6", 9800, "ra_p6")), false);
  await t("GROUP ROLE: CANNOT claim another group's publisher with its own group's assignment", () => setDoc(doc(juanGrp, "monthlyReports", "g6_forged"), rr("pubG6", 9801, "ra_p5")), false);
  await t("GROUP ROLE: CANNOT write without the publisher's role assignment", () => setDoc(doc(juanGrp, "monthlyReports", "g5_noassign"), rr("pubG5", 9802, null)), false);
  await t("GROUP ROLE: CANNOT use an inactive role assignment", () => setDoc(doc(juanGrp, "monthlyReports", "g5_inactive"), rr("pubG5", 9803, "ra_p5_old")), false);
  await t("GROUP ROLE: CANNOT edit another group's existing record", () => updateDoc(doc(juanGrp, "monthlyReports", "g6_existing"), { bibleStudiesCount: 9 }), false);
  await t("GROUP ROLE: CANNOT move a record to another publisher", () => updateDoc(doc(juanGrp, "monthlyReports", "g5_existing"), { publisherPersonId: "pubG6", groupRoleAssignmentId: "ra_p6" }), false);
  await t("GROUP ROLE: CAN delete a stamped record of its own group's publisher", () => deleteDoc(doc(juanGrp, "monthlyReports", "g5_existing")), true);
  await t("GROUP ROLE: CANNOT delete another group's record", () => deleteDoc(doc(juanGrp, "monthlyReports", "g6_existing")), false);
  await t("GROUP ROLE: CANNOT delete an unstamped record (the client stamps it first)", () => deleteDoc(doc(juanGrp, "monthlyReports", "g5_plain")), false);
  await t("GROUP ROLE: a person who does not fill a slot of that group CANNOT act for it", () => setDoc(doc(fakeGrp, "monthlyReports", "fake_new"), rr("pubG5", 9804, "ra_p5")), false);
  await t("GROUP ROLE: a Regular Elder without a group CANNOT write other publishers' records", () => setDoc(doc(plainElder, "monthlyReports", "plain_new"), rr("pubG5", 9805, "ra_p5")), false);
  await t("GROUP ROLE: the month lock applies to group roles too", () => setDoc(doc(juanGrp, "monthlyReports", "g5_locked"), rr("pubG5", 9700, "ra_p5")), false);
  await t("ACTIVE ROLE: acting as Coordinator Elder CAN write any publisher's record", () => setDoc(doc(juanCe, "monthlyReports", "ce_any"), rr("pubG6", 9806, null)), true);
  await t("ACTIVE ROLE: acting as Coordinator Elder CAN delete a record of the congregation", () => deleteDoc(doc(juanCe, "monthlyReports", "g6_existing")), true);
  await t("ACTIVE ROLE: another congregation's Coordinator Elder CANNOT write", () => setDoc(doc(secB, "monthlyReports", "foreign"), rr("pubG5", 9807, null)), false);
  await t("ACTIVE ROLE: a publisher CANNOT delete a record", () => deleteDoc(doc(pub, "monthlyReports", "g5_plain")), false);

  // ---- Level 1: the publisher's own submission (Open → Submitted → Access Requested → Access Granted → Submitted; or Reversed) ----
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const own = (m, status, extra = {}) => ({ publisherPersonId: "pubA", congregationId: "solano", periodMonth: m, status, bibleStudiesCount: 1, ...extra });
    await setDoc(doc(db, "monthlyReports", "p_submitted"), own(10000, "SUBMITTED"));
    await setDoc(doc(db, "monthlyReports", "p_requested"), own(10100, "ACCESS_REQUESTED", { accessRequestReason: "typo" }));
    await setDoc(doc(db, "monthlyReports", "p_granted"), own(10200, "ACCESS_GRANTED"));
    await setDoc(doc(db, "monthlyReports", "p_reversed"), own(10300, "RETURNED"));
    await setDoc(doc(db, "monthlyReports", "p_submitted2"), own(10400, "SUBMITTED"));
    await setDoc(doc(db, "monthlyReports", "p_reqG5"), { ...own(10500, "ACCESS_REQUESTED"), publisherPersonId: "pubG5", groupRoleAssignmentId: "ra_p5" });
    await setDoc(doc(db, "monthlyReports", "p_reqG6"), { ...own(10600, "ACCESS_REQUESTED"), publisherPersonId: "pubG6", groupRoleAssignmentId: "ra_p6" });
    await setDoc(doc(db, "monthlyReports", "p_submitted3"), own(10700, "SUBMITTED"));
    await setDoc(doc(db, "monthlyReports", "p_posted"), own(10800, "POSTED"));
  });
  const ownRec = (m, status, extra = {}) => ({ publisherPersonId: "pubA", congregationId: "solano", periodMonth: m, status, bibleStudiesCount: 1, ...extra });
  await t("PUBLISHER: CAN submit (Open → Submitted)", () => setDoc(doc(pub, "monthlyReports", "p_open"), ownRec(9990, "SUBMITTED")), true);
  await t("PUBLISHER: CANNOT edit their own submitted report", () => updateDoc(doc(pub, "monthlyReports", "p_submitted"), { bibleStudiesCount: 9 }), false);
  await t("PUBLISHER: CANNOT delete their own submitted report", () => deleteDoc(doc(pub, "monthlyReports", "p_submitted")), false);
  await t("PUBLISHER: CAN request edit access (Submitted → Access Requested) with a reason", () => updateDoc(doc(pub, "monthlyReports", "p_submitted"), { status: "ACCESS_REQUESTED", accessRequestReason: "I entered 2 hours instead of 3", accessRequestedAt: 5, lastEditedAt: 5 }), true);
  await t("PUBLISHER: CAN request access for a posted report too", () => updateDoc(doc(pub, "monthlyReports", "p_posted"), { status: "ACCESS_REQUESTED", accessRequestReason: "fix", accessRequestedAt: 5, lastEditedAt: 5 }), true);
  await t("PUBLISHER: the request CANNOT smuggle in other changes", () => updateDoc(doc(pub, "monthlyReports", "p_submitted2"), { status: "ACCESS_REQUESTED", accessRequestReason: "x", bibleStudiesCount: 50 }), false);
  await t("PUBLISHER: CANNOT edit while the access request is pending", () => updateDoc(doc(pub, "monthlyReports", "p_requested"), { bibleStudiesCount: 7 }), false);
  await t("PUBLISHER: CANNOT grant themselves access", () => updateDoc(doc(pub, "monthlyReports", "p_requested"), { status: "ACCESS_GRANTED" }), false);
  await t("PUBLISHER: CANNOT mark their own report Reversed or Posted", () => setDoc(doc(pub, "monthlyReports", "p_self"), ownRec(9991, "RETURNED")), false);
  await t("PUBLISHER: CANNOT create a report already Access Granted", () => setDoc(doc(pub, "monthlyReports", "p_self2"), ownRec(9992, "ACCESS_GRANTED")), false);
  await t("PUBLISHER: with Access Granted CAN correct the report", () => updateDoc(doc(pub, "monthlyReports", "p_granted"), { bibleStudiesCount: 4 }), true);
  await t("PUBLISHER: ...and submit it again (Access Granted → Submitted/Corrected)", () => updateDoc(doc(pub, "monthlyReports", "p_granted"), { status: "CORRECTED", bibleStudiesCount: 4 }), true);
  await t("PUBLISHER: once resubmitted the report is locked again", () => updateDoc(doc(pub, "monthlyReports", "p_granted"), { bibleStudiesCount: 8 }), false);
  await t("PUBLISHER: a Reversed report CAN be edited and submitted again", () => updateDoc(doc(pub, "monthlyReports", "p_reversed"), { status: "CORRECTED", bibleStudiesCount: 3 }), true);
  await t("PUBLISHER: another publisher CANNOT request access for them", () => updateDoc(doc(as("pubB"), "monthlyReports", "p_submitted3"), { status: "ACCESS_REQUESTED", accessRequestReason: "x", lastEditedAt: 5 }), false);
  // The person in charge decides
  await t("IN CHARGE: Service Overseer CAN approve (Access Requested → Access Granted)", () => updateDoc(doc(soA, "monthlyReports", "p_requested"), { status: "ACCESS_GRANTED", accessGrantedByPersonId: "soA", accessGrantedAt: 6 }), true);
  await t("IN CHARGE: Secretary CAN reject (back to Submitted)", () => updateDoc(doc(secA, "monthlyReports", "p_posted"), { status: "SUBMITTED", accessDecisionNote: "not needed" }), true);
  await t("IN CHARGE: Coordinator Elder CAN reverse a submitted report (Submitted → Reversed)", () => updateDoc(doc(ceA, "monthlyReports", "p_submitted2"), { status: "RETURNED", correctionReason: "recount" }), true);
  await t("IN CHARGE: another congregation's Secretary CANNOT reverse", () => updateDoc(doc(secB, "monthlyReports", "p_submitted3"), { status: "RETURNED", correctionReason: "x" }), false);
  await t("IN CHARGE: a plain Publisher CANNOT approve another's request", () => updateDoc(doc(as("pubB"), "monthlyReports", "p_submitted3"), { status: "ACCESS_GRANTED" }), false);
  await t("IN CHARGE: a Group Coordinator CAN approve access for a publisher of its own FS Group", () => updateDoc(doc(juanGrp, "monthlyReports", "p_reqG5"), { status: "ACCESS_GRANTED", accessGrantedByPersonId: "juanGrp" }), true);
  await t("IN CHARGE: a Group Coordinator CANNOT approve access for another FS Group", () => updateDoc(doc(juanGrp, "monthlyReports", "p_reqG6"), { status: "ACCESS_GRANTED", accessGrantedByPersonId: "juanGrp" }), false);
  // Level 2 lock: a month already submitted to the Circuit Overseer cannot be reopened or requested
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "coFieldServiceMonthStatus", "solano_11000"), { congregationId: "solano", periodMonth: 11000, status: "SUBMITTED", version: 1, updatedAt: 1 });
    await setDoc(doc(db, "monthlyReports", "p_colocked"), ownRec(11000, "SUBMITTED"));
  });
  await t("CO LEVEL: the publisher CANNOT request access while the congregation report is with the CO", () => updateDoc(doc(pub, "monthlyReports", "p_colocked"), { status: "ACCESS_REQUESTED", accessRequestReason: "x", lastEditedAt: 5 }), false);
  await t("CO LEVEL: the person in charge CANNOT reverse it while the congregation report is with the CO", () => updateDoc(doc(soA, "monthlyReports", "p_colocked"), { status: "RETURNED", correctionReason: "x" }), false);

  // ---- Meeting Attendance ----
  const SEP = 1788192000000; // 1 Sep 2026 00:00 (UTC+8)
  const OCT = 1790784000000; // 1 Oct 2026 00:00 (UTC+8)
  const d1 = SEP + 2 * DAY; // 3 Sep
  const mid = (cong, date, parts, extra = {}) => {
    const avg = (parts[0] + parts[1] + parts[2]) / 3;
    return { congregationId: cong, meetingType: "MIDWEEK", meetingDate: date, serviceMonth: Math.floor((date + 8 * 3600000) / 1) && (date >= OCT ? OCT : SEP),
      treasuresAttendance: parts[0], applyYourselfAttendance: parts[1], livingAsChristiansAttendance: parts[2], publicMeetingAttendance: null, watchtowerStudyAttendance: null,
      calculatedAverage: avg, officialAttendance: Math.floor(avg + 0.5), roundingMode: "ROUNDED", remarks: null, deleted: false, createdBy: "x", createdAt: 1, updatedBy: "", updatedAt: 1, ...extra };
  };
  const wk = (cong, date, parts, mode = "ROUNDED", extra = {}) => {
    const avg = (parts[0] + parts[1]) / 2;
    return { congregationId: cong, meetingType: "WEEKEND", meetingDate: date, serviceMonth: date >= OCT ? OCT : SEP,
      treasuresAttendance: null, applyYourselfAttendance: null, livingAsChristiansAttendance: null, publicMeetingAttendance: parts[0], watchtowerStudyAttendance: parts[1],
      calculatedAverage: avg, officialAttendance: mode === "ROUNDED" ? Math.floor(avg + 0.5) : avg, roundingMode: mode, remarks: null, deleted: false, createdBy: "x", createdAt: 1, updatedBy: "", updatedAt: 1, ...extra };
  };
  const att = (db, id) => doc(db, "meetingAttendance", id);
  const by = (who, rec) => ({ ...rec, updatedBy: who });
  const midId = (cong, date) => `${cong}_MIDWEEK_${date}`;
  const wkId = (cong, date) => `${cong}_WEEKEND_${date}`;

  await t("ATTENDANCE: Admin CAN add a Midweek record (79/80/85 → 81.33 → 81)", () => setDoc(att(admin, midId("solano", d1)), by("adminA", mid("solano", d1, [79, 80, 85]))), true);
  await t("ATTENDANCE: Coordinator Elder CAN add a Weekend record (92/105 → 98.5 → 99)", () => setDoc(att(ceA, wkId("solano", d1 + DAY)), by("ceA", wk("solano", d1 + DAY, [92, 105]))), true);
  await t("ATTENDANCE: Service Overseer CAN add one with the exact average kept (98.50)", () => setDoc(att(soA, wkId("solano", d1 + 5 * DAY)), by("soA", wk("solano", d1 + 5 * DAY, [92, 105], "EXACT"))), true);
  await t("ATTENDANCE: Secretary CAN add a Midweek record", () => setDoc(att(secA, midId("solano", d1 + 7 * DAY)), by("secA", mid("solano", d1 + 7 * DAY, [83, 82, 84]))), true);
  await t("ATTENDANCE: a wrong official figure is refused (82 instead of 81)", () => setDoc(att(admin, midId("solano", d1 + 8 * DAY)), by("adminA", mid("solano", d1 + 8 * DAY, [79, 80, 85], { officialAttendance: 82 }))), false);
  await t("ATTENDANCE: a wrong average is refused", () => setDoc(att(admin, midId("solano", d1 + 9 * DAY)), by("adminA", mid("solano", d1 + 9 * DAY, [79, 80, 85], { calculatedAverage: 90 }))), false);
  await t("ATTENDANCE: rounding down is refused (98.5 must round up to 99)", () => setDoc(att(admin, wkId("solano", d1 + 10 * DAY)), by("adminA", wk("solano", d1 + 10 * DAY, [92, 105], "ROUNDED", { officialAttendance: 98 }))), false);
  await t("ATTENDANCE: a negative count is refused", () => setDoc(att(admin, midId("solano", d1 + 11 * DAY)), by("adminA", mid("solano", d1 + 11 * DAY, [79, -1, 85]))), false);
  await t("ATTENDANCE: a decimal count is refused", () => setDoc(att(admin, midId("solano", d1 + 12 * DAY)), by("adminA", mid("solano", d1 + 12 * DAY, [79, 80, 85], { treasuresAttendance: 79.5 }))), false);
  await t("ATTENDANCE: a Midweek record cannot carry Weekend counts", () => setDoc(att(admin, midId("solano", d1 + 13 * DAY)), by("adminA", mid("solano", d1 + 13 * DAY, [79, 80, 85], { publicMeetingAttendance: 90 }))), false);
  await t("ATTENDANCE: a missing part is refused", () => setDoc(att(admin, wkId("solano", d1 + 14 * DAY)), by("adminA", wk("solano", d1 + 14 * DAY, [92, 105], "ROUNDED", { watchtowerStudyAttendance: null }))), false);
  await t("ATTENDANCE: the id must be congregation + type + date (no duplicates under another id)", () => setDoc(att(admin, "solano_dup"), by("adminA", mid("solano", d1 + 15 * DAY, [70, 70, 70]))), false);
  await t("ATTENDANCE: the service month must match the meeting date", () => setDoc(att(admin, midId("solano", d1 + 16 * DAY)), by("adminA", mid("solano", d1 + 16 * DAY, [70, 70, 70], { serviceMonth: OCT }))), false);
  await t("ATTENDANCE: records are written as yourself", () => setDoc(att(admin, midId("solano", d1 + 17 * DAY)), mid("solano", d1 + 17 * DAY, [70, 70, 70], { updatedBy: "someoneElse" })), false);
  await t("ATTENDANCE: another congregation's Secretary CANNOT add", () => setDoc(att(secB, midId("solano", d1 + 18 * DAY)), by("secB", mid("solano", d1 + 18 * DAY, [70, 70, 70]))), false);
  await t("ATTENDANCE: a Publisher CANNOT add", () => setDoc(att(pub, midId("solano", d1 + 19 * DAY)), by("pubA", mid("solano", d1 + 19 * DAY, [70, 70, 70]))), false);
  await t("ATTENDANCE: a Group Coordinator CANNOT add (not a congregation-wide role)", () => setDoc(att(juanGrp, midId("solano", d1 + 20 * DAY)), by("juanGrp", mid("solano", d1 + 20 * DAY, [70, 70, 70]))), false);
  await t("ATTENDANCE: the Circuit Overseer CANNOT add", () => setDoc(att(coA, midId("solano", d1 + 21 * DAY)), by("coA", mid("solano", d1 + 21 * DAY, [70, 70, 70]))), false);
  await t("ATTENDANCE: the Super-Admin CAN add anywhere", () => setDoc(att(su, midId("bambang", d1)), by("superAdmin1", mid("bambang", d1, [60, 61, 62]))), true);
  await t("ATTENDANCE: an existing record CAN be edited (counts change, mean recalculated)", () => updateDoc(att(admin, midId("solano", d1)), { treasuresAttendance: 85, calculatedAverage: 83.3333333333, officialAttendance: 83, updatedBy: "adminA" }), true);
  await t("ATTENDANCE: the meeting date of a record CANNOT be changed", () => updateDoc(att(admin, midId("solano", d1)), { meetingDate: d1 + DAY, updatedBy: "adminA" }), false);
  await t("ATTENDANCE: a record CAN be deleted softly (deleted = true)", () => updateDoc(att(admin, midId("solano", d1)), { deleted: true, updatedBy: "adminA" }), true);
  await t("ATTENDANCE: a record can NEVER be hard-deleted by the congregation", () => deleteDoc(att(admin, wkId("solano", d1 + DAY))), false);

  // reads
  await t("ATTENDANCE READ: the congregation's own manager reads its records", () => getDoc(att(admin, wkId("solano", d1 + DAY))), true);
  await t("ATTENDANCE READ: a Publisher of the congregation reads them too", () => getDoc(att(pub, wkId("solano", d1 + DAY))), true);
  await t("ATTENDANCE READ: another congregation's Secretary CANNOT read them", () => getDoc(att(secB, wkId("solano", d1 + DAY))), false);
  await t("ATTENDANCE READ: the Circuit Overseer reads assigned congregations (filtered)", () => getDocs(query(collection(coA, "meetingAttendance"), where("congregationId", "in", ["solano", "bayombong"]))), true);
  await t("ATTENDANCE READ: ...but not another circuit's", () => getDoc(att(coB, wkId("solano", d1 + DAY))), false);
  await t("ATTENDANCE READ: ...and not unfiltered", () => getDocs(collection(coA, "meetingAttendance")), false);
  await t("ATTENDANCE READ: own congregation list works for a manager (filtered by own congregation)", () => getDocs(query(collection(admin, "meetingAttendance"), where("congregationId", "==", "solano"))), true);

  // settings
  const setg = (db, cong) => doc(db, "meetingAttendanceSettings", cong);
  await t("SETTINGS: Admin CAN set the rounding preference", () => setDoc(setg(admin, "solano"), { congregationId: "solano", roundingMode: "EXACT", updatedBy: "adminA", updatedAt: 1 }), true);
  await t("SETTINGS: a Publisher CANNOT", () => setDoc(setg(pub, "solano"), { congregationId: "solano", roundingMode: "EXACT", updatedBy: "pubA", updatedAt: 1 }), false);
  await t("SETTINGS: another congregation's Secretary CANNOT", () => setDoc(setg(secB, "solano"), { congregationId: "solano", roundingMode: "ROUNDED", updatedBy: "secB", updatedAt: 1 }), false);
  await t("SETTINGS: an unknown mode is refused", () => setDoc(setg(admin, "solano"), { congregationId: "solano", roundingMode: "CEIL", updatedBy: "adminA", updatedAt: 1 }), false);
  await t("SETTINGS: the Circuit Overseer CANNOT change it", () => setDoc(setg(coA, "solano"), { congregationId: "solano", roundingMode: "ROUNDED", updatedBy: "coA", updatedAt: 1 }), false);
  await t("SETTINGS: everyone signed in reads it", () => getDoc(setg(pub, "solano")), true);

  // audit
  const aev = (db, id) => doc(db, "meetingAttendanceEvents", id);
  const aevData = (who, extra = {}) => ({ congregationId: "solano", recordId: "r", action: "Attendance added", at: 1, userId: who, ...extra });
  await t("ATT AUDIT: a manager CAN append an audit line as themselves", () => setDoc(aev(admin, "ae1"), aevData("adminA")), true);
  await t("ATT AUDIT: not as someone else", () => setDoc(aev(admin, "ae2"), aevData("secA")), false);
  await t("ATT AUDIT: a Publisher CANNOT", () => setDoc(aev(pub, "ae3"), aevData("pubA")), false);
  await t("ATT AUDIT: lines are never edited", () => updateDoc(aev(admin, "ae1"), { action: "x" }), false);
  await t("ATT AUDIT: lines are never deleted", () => deleteDoc(aev(admin, "ae1")), false);
  await t("ATT AUDIT: the Circuit Overseer reads them", () => getDoc(aev(coA, "ae1")), true);
  await t("ATT AUDIT: a Publisher cannot read them", () => getDoc(aev(pub, "ae1")), false);

  // historical monthly statistics, tied to the report's status
  const AUGM = 1785513600000; // 1 Aug 2026 00:00 (UTC+8) — a month nothing else in this file touches
  const aug1 = AUGM + 2 * DAY;
  await t("STATS (setup): an August weekend record exists", () => setDoc(att(admin, wkId("solano", aug1)), by("adminA", wk("solano", aug1, [92, 105], "ROUNDED", { serviceMonth: AUGM }))), true);
  const stat = (db, id) => doc(db, "congregationMonthlyStatistics", id);
  const sdata = (m, status, extra = {}) => ({ congregationId: "solano", serviceMonth: m, fieldServiceReportCount: 40, elderCount: 8, publisherCount: 100, snapshotStatus: status, updatedAt: 1, ...extra });
  const wsd = (db, m, reportStatus, extra = {}) => { const b = writeBatch(db); b.set(doc(db, "coFieldServiceMonthStatus", `solano_${m}`), { congregationId: "solano", periodMonth: m, status: reportStatus, version: 1, updatedAt: 1, ...extra }); return b; };
  await t("STATS: sending the month CAN create its statistics snapshot (same batch as the report status)", async () => {
    const b = wsd(admin, AUGM, "SUBMITTED", { submittedAt: 1 }); b.set(stat(admin, `solano_${AUGM}`), sdata(AUGM, "SUBMITTED")); await b.commit();
  }, true);
  await t("STATS: a snapshot cannot be created without the report being submitted", () => setDoc(stat(admin, `solano_${OCT}`), sdata(OCT, "SUBMITTED")), false);
  await t("STATS: a snapshot cannot claim a status the report does not have", async () => {
    const b = wsd(admin, OCT, "SUBMITTED", { submittedAt: 1 }); b.set(stat(admin, `solano_${OCT}`), sdata(OCT, "RECEIVED")); await b.commit();
  }, false);
  await t("STATS: a Publisher CANNOT create statistics", async () => {
    const b = writeBatch(pub); b.set(doc(pub, "coFieldServiceMonthStatus", `solano_${OCT}`), { congregationId: "solano", periodMonth: OCT, status: "SUBMITTED", version: 1, updatedAt: 1 }); b.set(stat(pub, `solano_${OCT}`), sdata(OCT, "SUBMITTED")); await b.commit();
  }, false);
  await t("STATS: the Circuit Overseer CANNOT edit the numbers", async () => {
    const b = writeBatch(coA); b.update(stat(coA, `solano_${AUGM}`), { publisherCount: 999, snapshotStatus: "SUBMITTED" }); b.update(doc(coA, "coFieldServiceMonthStatus", `solano_${AUGM}`), { coRemarks: "x", coRemarksAt: 1, updatedAt: 3 }); await b.commit();
  }, false);
  await t("STATS: receiving the report freezes its statistics (status moves with the report)", async () => {
    const b = writeBatch(coA); b.update(stat(coA, `solano_${AUGM}`), { snapshotStatus: "RECEIVED", receivedDate: 5, updatedAt: 5 }); b.update(doc(coA, "coFieldServiceMonthStatus", `solano_${AUGM}`), { status: "RECEIVED", receivedAt: 5, updatedAt: 5 }); await b.commit();
  }, true);
  await t("STATS: a frozen snapshot CANNOT be changed by the congregation", async () => {
    const b = writeBatch(admin); b.update(stat(admin, `solano_${AUGM}`), { elderCount: 1, snapshotStatus: "SUBMITTED", updatedAt: 6 }); await b.commit();
  }, false);
  await t("STATS: attendance of a frozen month CANNOT be added", () => setDoc(att(admin, midId("solano", AUGM + 20 * DAY)), by("adminA", mid("solano", AUGM + 20 * DAY, [70, 70, 70], { serviceMonth: AUGM }))), false);
  await t("STATS: attendance of a frozen month CANNOT be edited", () => updateDoc(att(admin, wkId("solano", aug1)), { remarks: "late", updatedBy: "adminA" }), false);
  await t("STATS: ...but the Super-Admin can", () => updateDoc(att(su, wkId("solano", aug1)), { remarks: "corrected", updatedBy: "superAdmin1" }), true);
  await t("STATS: returning the report opens the statistics again", async () => {
    const b = writeBatch(coA); b.update(stat(coA, `solano_${AUGM}`), { snapshotStatus: "RETURNED", receivedDate: null, updatedAt: 7 }); b.update(doc(coA, "coFieldServiceMonthStatus", `solano_${AUGM}`), { status: "RETURNED", returnedAt: 7, returnReason: "x", coRemarks: "x", updatedAt: 7 }); await b.commit();
  }, true);
  await t("STATS: after a return the attendance of that month can be edited again", () => updateDoc(att(admin, wkId("solano", aug1)), { remarks: "fixed", updatedBy: "adminA" }), true);
  await t("STATS: sending the corrected report again refreshes the snapshot (Returned → Submitted)", async () => {
    const b = writeBatch(admin); b.update(stat(admin, `solano_${AUGM}`), { elderCount: 9, snapshotStatus: "SUBMITTED", updatedAt: 8 });
    b.update(doc(admin, "coFieldServiceMonthStatus", `solano_${AUGM}`), { status: "SUBMITTED", version: 2, submittedAt: 8, submittedByName: "X", updatedAt: 8 }); await b.commit();
  }, true);
  await t("STATS: everyone signed in reads the snapshots", () => getDoc(stat(pub, `solano_${SEP}`)), true);


  // ================= Comparative Reports (formal workflow) =================
  const cmpId = (cong, a, b, c, d) => `${cong}_${a}_${b}_${c}_${d}`;
  const M = { jan: 1735689600000, mar: 1740787200000, apr: 1743465600000, jun: 1748736000000, jul: 1751328000000, sep: 1756684800000, far: 4070908800000 };
  const cmpDoc = (db, id) => doc(db, "congregationComparativeReports", id);
  const CID = cmpId("solano", M.jan, M.mar, M.apr, M.jun);
  const cmpNew = (over = {}) => ({
    congregationId: "solano", reportNumber: "CR-2026-0001", periodAStart: M.jan, periodAEnd: M.mar, periodBStart: M.apr, periodBEnd: M.jun, status: "DRAFT", version: 1,
    reportSnapshot: "{}", sourceSnapshotIds: [], attendanceRoundingMode: "ROUNDED", createdBy: "adminA", createdByName: "A", createdAt: 1, updatedAt: 1, ...over,
  });
  await t("CMP: a publisher CANNOT create a comparative report", () => setDoc(cmpDoc(pub, CID), cmpNew({ createdBy: "pubA" })), false);
  await t("CMP: the Circuit Overseer CANNOT create one", () => setDoc(cmpDoc(coA, CID), cmpNew({ createdBy: "coA" })), false);
  await t("CMP: another congregation's secretary CANNOT create one", () => setDoc(cmpDoc(secB, CID), cmpNew({ createdBy: "secB" })), false);
  await t("CMP: a future period CANNOT be created", () => { const id = cmpId("solano", M.jan, M.mar, M.apr, M.far); return setDoc(cmpDoc(admin, id), cmpNew({ periodBEnd: M.far })); }, false);
  await t("CMP: overlapping periods CANNOT be created", () => { const id = cmpId("solano", M.jan, M.apr, M.mar, M.jun); return setDoc(cmpDoc(admin, id), cmpNew({ periodAEnd: M.apr, periodBStart: M.mar })); }, false);
  await t("CMP: a wrongly numbered id CANNOT be created", () => setDoc(cmpDoc(admin, "whatever"), cmpNew()), false);
  await t("CMP: the congregation admin creates a draft", () => setDoc(cmpDoc(admin, CID), cmpNew()), true);
  await t("CMP: ...the same id cannot hold a second report with other content (id is the periods)", () => updateDoc(cmpDoc(admin, CID), { periodBEnd: M.jul }), false);
  await t("CMP: the Circuit Overseer CANNOT read a draft", () => getDoc(cmpDoc(coA, CID)), false);
  await t("CMP: ...and a status-filtered list does not include drafts (query returns none)", async () => {
    const s = await getDocs(query(collection(coA, "congregationComparativeReports"), where("congregationId", "in", ["solano"]), where("status", "in", ["SUBMITTED", "RETURNED", "RECEIVED"]))); if (s.size !== 0) throw new Error("saw a draft");
  }, true);
  await t("CMP: the admin edits the draft snapshot", () => updateDoc(cmpDoc(admin, CID), { reportSnapshot: "{\"x\":1}", updatedAt: 2 }), true);
  await t("CMP: a draft cannot jump to RECEIVED", () => updateDoc(cmpDoc(admin, CID), { status: "RECEIVED", receivedBy: "adminA", receivedAt: 3, updatedAt: 3 }), false);
  await t("CMP: the Circuit Overseer CANNOT edit a draft", () => updateDoc(cmpDoc(coA, CID), { currentCoRemarks: "x", updatedAt: 3 }), false);
  await t("CMP: the admin submits (DRAFT -> SUBMITTED)", () => updateDoc(cmpDoc(admin, CID), { status: "SUBMITTED", submittedBy: "adminA", submittedByName: "A", submittedByRole: "ADMIN", submittedAt: 3, updatedAt: 3 }), true);
  await t("CMP: submitted reports are readable by the Circuit Overseer (filtered)", () => getDocs(query(collection(coA, "congregationComparativeReports"), where("congregationId", "in", ["solano"]), where("status", "in", ["SUBMITTED", "RETURNED", "RECEIVED"]))), true);
  await t("CMP: another circuit's CO CANNOT read it", () => getDoc(cmpDoc(coB, CID)), false);
  await t("CMP: once Submitted the congregation CANNOT edit the snapshot", () => updateDoc(cmpDoc(admin, CID), { reportSnapshot: "{\"x\":2}", updatedAt: 4 }), false);
  await t("CMP: ...CANNOT delete it", () => deleteDoc(cmpDoc(admin, CID)), false);
  await t("CMP: ...CANNOT change its status back", () => updateDoc(cmpDoc(admin, CID), { status: "DRAFT", updatedAt: 4 }), false);
  await t("CMP: the CO CANNOT change the snapshot", () => updateDoc(cmpDoc(coA, CID), { reportSnapshot: "{}", currentCoRemarks: "x", updatedAt: 4 }), false);
  await t("CMP: the CO adds a remark (document + remark record)", async () => {
    const b = writeBatch(coA); b.update(cmpDoc(coA, CID), { currentCoRemarks: "Please check July", updatedAt: 4 });
    b.set(doc(coA, "comparativeReportRemarks", "r1"), { comparativeReportId: CID, congregationId: "solano", authorUserId: "coA", authorName: "CO", authorRole: "CIRCUIT_OVERSEER", remark: "Please check July", createdAt: 4 }); await b.commit();
  }, true);
  await t("CMP: a remark cannot be written in someone else's name", () => setDoc(doc(coA, "comparativeReportRemarks", "r2"), { comparativeReportId: CID, congregationId: "solano", authorUserId: "adminA", remark: "x", createdAt: 5 }), false);
  await t("CMP: the congregation CANNOT write a CO remark", () => setDoc(doc(admin, "comparativeReportRemarks", "r3"), { comparativeReportId: CID, congregationId: "solano", authorUserId: "adminA", remark: "x", createdAt: 5 }), false);
  await t("CMP: a remark cannot be edited afterwards", () => updateDoc(doc(coA, "comparativeReportRemarks", "r1"), { remark: "changed" }), false);
  await t("CMP: the CO returns it (reason required)", () => updateDoc(cmpDoc(coA, CID), { status: "RETURNED", returnedBy: "coA", returnedByName: "CO", returnedAt: 5, returnReason: "", updatedAt: 5 }), false);
  await t("CMP: the CO returns it with a reason", () => updateDoc(cmpDoc(coA, CID), { status: "RETURNED", returnedBy: "coA", returnedByName: "CO", returnedAt: 5, returnReason: "July attendance is missing", currentCoRemarks: "July attendance is missing", updatedAt: 5 }), true);
  await t("CMP: a returned report can be corrected by the congregation", () => updateDoc(cmpDoc(admin, CID), { reportSnapshot: "{\"x\":3}", updatedAt: 6 }), true);
  await t("CMP: ...but its periods CANNOT be changed", () => updateDoc(cmpDoc(admin, CID), { periodAStart: M.mar, updatedAt: 6 }), false);
  await t("CMP: ...and it is not deletable", () => deleteDoc(cmpDoc(admin, CID)), false);
  await t("CMP: resubmitting needs version + 1", () => updateDoc(cmpDoc(admin, CID), { status: "SUBMITTED", version: 1, submittedBy: "adminA", submittedByName: "A", submittedByRole: "ADMIN", submittedAt: 7, updatedAt: 7 }), false);
  await t("CMP: the corrected report is resubmitted (version 2)", () => updateDoc(cmpDoc(admin, CID), { status: "SUBMITTED", version: 2, submittedBy: "adminA", submittedByName: "A", submittedByRole: "ADMIN", submittedAt: 7, updatedAt: 7 }), true);
  await t("CMP: the CO receives it", () => updateDoc(cmpDoc(coA, CID), { status: "RECEIVED", receivedBy: "coA", receivedByName: "CO", receivedAt: 8, updatedAt: 8 }), true);
  await t("CMP: RECEIVED: the congregation CANNOT edit it", () => updateDoc(cmpDoc(admin, CID), { reportSnapshot: "{\"x\":4}", updatedAt: 9 }), false);
  await t("CMP: RECEIVED: ...CANNOT resubmit it", () => updateDoc(cmpDoc(admin, CID), { status: "SUBMITTED", version: 3, submittedBy: "adminA", updatedAt: 9 }), false);
  await t("CMP: RECEIVED: ...CANNOT delete it", () => deleteDoc(cmpDoc(admin, CID)), false);
  await t("CMP: RECEIVED: the CO CANNOT return it any more", () => updateDoc(cmpDoc(coA, CID), { status: "RETURNED", returnReason: "again", returnedAt: 9, updatedAt: 9 }), false);
  await t("CMP: RECEIVED: the CO CANNOT add remarks to it", () => setDoc(doc(coA, "comparativeReportRemarks", "r4"), { comparativeReportId: CID, congregationId: "solano", authorUserId: "coA", remark: "late", createdAt: 9 }), false);
  await t("CMP: RECEIVED: it is still readable by the congregation and the CO", async () => { await getDoc(cmpDoc(admin, CID)); await getDoc(cmpDoc(coA, CID)); }, true);
  await t("CMP: history lines can be added as yourself", () => setDoc(doc(admin, "comparativeReportHistory", "h1"), { reportId: CID, congregationId: "solano", action: "Draft created", userId: "adminA", at: 1 }), true);
  await t("CMP: ...not as somebody else", () => setDoc(doc(admin, "comparativeReportHistory", "h2"), { reportId: CID, congregationId: "solano", action: "x", userId: "coA", at: 1 }), false);
  await t("CMP: ...and never edited or deleted", async () => { await updateDoc(doc(admin, "comparativeReportHistory", "h1"), { action: "y" }); }, false);
  await t("CMP: a publisher CANNOT read history", () => getDoc(doc(pub, "comparativeReportHistory", "h1")), false);
  await t("CMP: a draft can be deleted by the congregation (history stays)", async () => {
    const id = cmpId("solano", M.jan, M.jan, M.mar, M.mar);
    await setDoc(cmpDoc(admin, id), cmpNew({ periodAEnd: M.jan, periodBStart: M.mar, periodBEnd: M.mar })); await deleteDoc(cmpDoc(admin, id));
  }, true);
  await t("CMP: the Super-Admin can read every report", () => getDoc(cmpDoc(su, CID)), true);

  await testEnv.cleanup();
  const failed = results.filter((r) => !r.pass);
  console.log(`\n=== SUMMARY ===\n${results.length - failed.length}/${results.length} passed`);
  if (failed.length) { failed.forEach((f) => console.log(`  - ${f.name}: ${f.detail}`)); process.exitCode = 1; }
}

run().catch((e) => { console.error("Test harness crashed:", e); process.exitCode = 1; });
