// Checks firestore.rules in the emulator. Run from this folder: npm install, then
// npx firebase emulators:exec --only firestore --project demo-rules "node test.mjs"
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { readFileSync } from 'fs';
import { doc, collection, runTransaction, serverTimestamp, setDoc, getDoc, Timestamp, updateDoc } from 'firebase/firestore';
const env = await initializeTestEnvironment({ projectId: 'demo-rules', firestore: { rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8085 } });
const good = { maker: 'Hornady', name: 'ELD Match', diameter_in: 0.264, weight_gr: 147, bc: { g1: 0.697, g7: 0.351 }, source: { type: 'website', ref: 'https://hornady.com/x' }, note: 'hi', app: '0.2.0' };
async function submit(db, uid, data = good, opts = {}) {
  const q = doc(db, 'quota', uid); const s = doc(collection(db, 'submissions'));
  return runTransaction(db, async t => {
    const cur = await t.get(q);
    const d = cur.exists() ? cur.data() : null;
    const sameDay = d && !opts.newDay;
    t.set(q, { last: serverTimestamp(), dayStart: sameDay ? d.dayStart : serverTimestamp(), count: sameDay ? d.count + 1 : 1, lastId: opts.lastId ?? s.id });
    t.set(s, { ...data, uid: opts.as ?? uid, createdAt: serverTimestamp(), status: opts.status ?? 'new' });
  });
}
let ok = 0, bad = 0;
async function check(name, p, expect) { try { await (expect ? assertSucceeds(p) : assertFails(p)); ok++; console.log('ok  ', name); } catch (e) { bad++; console.log('FAIL', name, e.message); } }
const alice = env.authenticatedContext('alice').firestore();
await check('first submission', submit(alice, 'alice'), true);
await check('second within a minute', submit(alice, 'alice'), false);
await check('unauthenticated', submit(env.unauthenticatedContext().firestore(), 'nobody'), false);
const bob = env.authenticatedContext('bob').firestore();
await check('bad bc', submit(bob, 'bob', { ...good, bc: { g1: 5 } }), false);
await check('extra field', submit(bob, 'bob', { ...good, evil: 1 }), false);
await check('uid spoof', submit(bob, 'bob', good, { as: 'alice' }), false);
await check('status accepted', submit(bob, 'bob', good, { status: 'accepted' }), false);
await check('long name', submit(bob, 'bob', { ...good, name: 'x'.repeat(61) }), false);
await check('bad source type', submit(bob, 'bob', { ...good, source: { type: 'x', ref: 'abc' } }), false);
await check('submission without quota', setDoc(doc(bob, 'submissions', 'zzz'), { ...good, uid: 'bob', createdAt: serverTimestamp(), status: 'new' }), false);
await check('bob valid', submit(bob, 'bob'), true);
await check('read other quota', getDoc(doc(bob, 'quota', 'alice')), false);
await check('update own submission', (async () => { const q = await getDoc(doc(bob, 'quota', 'bob')); await updateDoc(doc(bob, 'submissions', q.data().lastId), { status: 'accepted' }); })(), false);
await check('read own submission', (async () => { const q = await getDoc(doc(bob, 'quota', 'bob')); await getDoc(doc(bob, 'submissions', q.data().lastId)); })(), true);
// day limit: set quota as admin to count 10, last 2 min ago, dayStart 1 h ago
await env.withSecurityRulesDisabled(async c => { const db = c.firestore(); await setDoc(doc(db, 'quota', 'carol'), { last: Timestamp.fromMillis(Date.now() - 120000), dayStart: Timestamp.fromMillis(Date.now() - 3600000), count: 10, lastId: 'x' }); await setDoc(doc(db, 'quota', 'dave'), { last: Timestamp.fromMillis(Date.now() - 120000), dayStart: Timestamp.fromMillis(Date.now() - 3600000), count: 3, lastId: 'x' }); await setDoc(doc(db, 'quota', 'erin'), { last: Timestamp.fromMillis(Date.now() - 120000), dayStart: Timestamp.fromMillis(Date.now() - 90000000), count: 10, lastId: 'x' }); await setDoc(doc(db, 'blocked', 'mallory'), { reason: 'spam' }); });
await check('11th in a day', submit(env.authenticatedContext('carol').firestore(), 'carol'), false);
await check('4th in a day', submit(env.authenticatedContext('dave').firestore(), 'dave'), true);
await check('new day resets', submit(env.authenticatedContext('erin').firestore(), 'erin', good, { newDay: true }), true);
await check('new day but continuing count', submit(env.authenticatedContext('erin').firestore(), 'erin'), false);
await check('blocked', submit(env.authenticatedContext('mallory').firestore(), 'mallory'), false);
await check('users own doc', setDoc(doc(alice, 'users', 'alice'), { data: '{}' }), true);
await check('users other doc', setDoc(doc(alice, 'users', 'bob'), { data: '{}' }), false);
console.log(ok, 'passed', bad, 'failed'); await env.cleanup(); process.exit(bad ? 1 : 0);
