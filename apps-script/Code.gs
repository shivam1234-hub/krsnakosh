/**
 * Tag Ledger phone inbox.
 *
 * - Android app: keeps an "Expenses" tab in step with the phone, and saves proof photos to Drive, viewable by anyone with the link.
 * - Web version: MacroDroid POSTs bank SMS to the "Inbox" tab; Tag Ledger on localhost reads them back.
 * Everything is stored in the Google Sheet this script is attached to.
 *
 * Setup: see ../SETUP.md
 */

// Change this to a long random string, and use the same value in MacroDroid and in Tag Ledger.
const SECRET = 'CHANGE-ME-to-a-long-random-string';

const SHEET_NAME = 'Inbox';
const PHOTO_FOLDER = 'Tag Ledger proofs';
const MAX_ROWS_PER_READ = 500;

function doPost(e) {
  const body = readBody_(e);
  if (body.token !== SECRET) return json_({ ok: false, error: 'bad token' });

  if (body.action === 'photo') return savePhoto_(body);
  if (body.action === 'info') {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    return json_({ ok: true, sheetUrl: ss.getUrl(), sheetName: ss.getName() });
  }
  if (body.action === 'upsert') return upsertEntries_(body.entries || []);
  if (body.action === 'delete') return deleteEntries_(body.ids || []);

  const text = String(body.text || '').trim();
  if (!text) return json_({ ok: false, error: 'empty text' });
  const id = Utilities.getUuid();
  sheet_().appendRow([id, Date.now(), String(body.sender || '').slice(0, 100), text.slice(0, 2000)]);
  return json_({ ok: true, id: id });
}

function doGet(e) {
  const p = (e && e.parameter) || {};
  if (p.token !== SECRET) return json_({ ok: false, error: 'bad token' });
  const since = Number(p.since || 0);
  const values = sheet_().getDataRange().getValues().slice(1);
  const rows = values
    .filter(function (r) { return Number(r[1]) > since; })
    .slice(-MAX_ROWS_PER_READ)
    .map(function (r) { return { id: String(r[0]), received: Number(r[1]), sender: String(r[2]), text: String(r[3]) }; });
  return json_({ ok: true, rows: rows });
}

// Saves a base64 image to the Drive folder and makes it viewable by anyone who has the link.
function savePhoto_(body) {
  const data = String(body.data || '');
  if (!data) return json_({ ok: false, error: 'no photo' });
  const mime = /^image\/(png|jpeg|webp|heic)$/.test(body.mime) ? body.mime : 'image/jpeg';
  const name = String(body.name || 'proof').replace(/[^\w .-]+/g, '_').slice(0, 80);
  const blob = Utilities.newBlob(Utilities.base64Decode(data), mime, name);
  const file = folder_().createFile(blob);
  file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  return json_({ ok: true, id: file.getId(), url: file.getUrl() });
}

// Run once from the editor to make proof photos uploaded before this change viewable by link too.
function makeAllProofsPublic() {
  const files = folder_().getFiles();
  let n = 0;
  while (files.hasNext()) { files.next().setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW); n++; }
  Logger.log(n + ' photos are now viewable by anyone with the link');
}

// ---------- "Expenses" tab, kept in step with the Android app ----------

const EXPENSE_COLS = ['id', 'date', 'time', 'title', 'type', 'amount', 'signed', 'tags', 'note', 'payee', 'ref', 'photo', 'currency', 'updated'];
const EXPENSE_HEADERS = ['ID', 'Date', 'Time', 'Title', 'Type', 'Amount', 'Signed amount', 'Tags', 'Note', 'Payee', 'UPI ref', 'Proof photo', 'Currency', 'Updated (ms)'];

function expensesSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName('Expenses');
  if (!sh) {
    sh = ss.insertSheet('Expenses', 0);
    sh.appendRow(EXPENSE_HEADERS);
    sh.setFrozenRows(1);
    sh.getRange('B:B').setNumberFormat('@'); // keep dates as typed, e.g. 2026-10-06
  }
  return sh;
}

function upsertEntries_(entries) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const sh = expensesSheet_();
    const ids = sh.getLastRow() > 1 ? sh.getRange(2, 1, sh.getLastRow() - 1, 1).getValues().map(function (r) { return String(r[0]); }) : [];
    const rowOf = {};
    ids.forEach(function (id, i) { rowOf[id] = i + 2; });
    const append = [];
    entries.forEach(function (e) {
      const row = EXPENSE_COLS.map(function (k) { return e[k] === undefined ? '' : e[k]; });
      if (rowOf[e.id]) sh.getRange(rowOf[e.id], 1, 1, row.length).setValues([row]);
      else append.push(row);
    });
    if (append.length) sh.getRange(sh.getLastRow() + 1, 1, append.length, EXPENSE_COLS.length).setValues(append);
    return json_({ ok: true, updated: entries.length - append.length, added: append.length });
  } finally {
    lock.releaseLock();
  }
}

function deleteEntries_(ids) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const sh = expensesSheet_();
    if (sh.getLastRow() < 2) return json_({ ok: true, deleted: 0 });
    const want = {};
    ids.forEach(function (id) { want[String(id)] = true; });
    const col = sh.getRange(2, 1, sh.getLastRow() - 1, 1).getValues();
    let n = 0;
    for (let i = col.length - 1; i >= 0; i--) if (want[String(col[i][0])]) { sh.deleteRow(i + 2); n++; }
    return json_({ ok: true, deleted: n });
  } finally {
    lock.releaseLock();
  }
}

function readBody_(e) {
  const params = (e && e.parameter) || {};
  const raw = e && e.postData && e.postData.contents;
  if (raw && /^\s*\{/.test(raw)) {
    try { return Object.assign({}, params, JSON.parse(raw)); } catch (err) { /* fall through to form fields */ }
  }
  return params;
}

function sheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEET_NAME);
  if (!sh) {
    sh = ss.insertSheet(SHEET_NAME);
    sh.appendRow(['id', 'received (ms)', 'sender', 'text']);
    sh.setFrozenRows(1);
  }
  return sh;
}

function folder_() {
  const it = DriveApp.getFoldersByName(PHOTO_FOLDER);
  return it.hasNext() ? it.next() : DriveApp.createFolder(PHOTO_FOLDER);
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

// Run this once from the editor (select testInbox, click Run) to grant permissions and check the sheet works.
function testInbox() {
  doPost({ parameter: { token: SECRET, sender: 'TEST', text: 'Sent Rs.1.00 From HDFC Bank A/C *1234 To TEST SHOP On 06/10/26 Ref 600000000001' } });
  Logger.log(doGet({ parameter: { token: SECRET, since: '0' } }).getContent());
}
