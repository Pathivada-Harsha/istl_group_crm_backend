/*
 * Checks the built skeleton for the layout guarantees the proposal depends on.
 * Same assertions as SolarProposalDocTest, but runs without a JVM — handy when
 * the machine has no memory left to fork a test JVM.
 *
 * Usage: node verifyTemplate.js [path/to/solar-proposal-template.docx]
 */
const fs = require('fs');
const { readZip } = require('./zip');
const { children, paragraphSpans, paraText, rowSpans } = require('./docxlib');

const TPL = process.argv[2] || '../../src/main/resources/proposal-templates/solar-proposal-template.docx';
const entries = readZip(fs.readFileSync(TPL));
const part = (n) => entries.find(e => e.name === n).data.toString('utf8');

const doc = part('word/document.xml');
const failures = [];
const ok = [];
function check(label, condition, detail) {
  (condition ? ok : failures).push(label + (condition ? '' : '  — ' + detail));
}

// ── 1. Nothing forces a page break ─────────────────────────────────────────
const nextPage = (doc.match(/w:type w:val="nextPage"/g) || []).length;
const continuous = (doc.match(/w:type w:val="continuous"/g) || []).length;
const sectPr = (doc.match(/<w:sectPr/g) || []).length;
check('no nextPage section breaks', nextPage === 0, `found ${nextPage}`);
check('no manual page breaks', !doc.includes('w:br w:type="page"'), 'found one');
check('one continuous break (installs header/footer)', continuous === 1, `found ${continuous}`);
check('two sections total (cover + body)', sectPr === 2, `found ${sectPr}`);

// ── 2. Every heading is bound to what follows ──────────────────────────────
// Matched on the paragraph's WHOLE text — "Metering" alone also appears in the
// "Grid Synchronization & Net Metering" bullet, which must not be pinned.
const HEADINGS = [
  'Our Company Overview', 'Our Expertise', 'EPC Capabilities', 'Innovation & R&D',
  'Global Presence:', 'Safety & Quality Commitment:', 'Our safety practices include:',
  'Our Sample PROJECT EXECUTION / INSTALLATION WORKS', 'About the System',
  'How Net Metering Works', 'Financial Pricing', 'Subsidy Details:-',
  'Note:', 'Warranty:', 'Bill of Material',
];
const leafParas = paragraphSpans(doc)
  .filter(sp => !/<w:p[\s>]/.test(doc.slice(doc.indexOf('>', sp.start) + 1, sp.end)))
  .map(sp => doc.slice(sp.start, sp.end));
const textOf = (p) => paraText(p).replace(/\s+/g, ' ').trim();

for (const heading of HEADINGS) {
  const p = leafParas.find(x => textOf(x) === heading);
  check(`keepNext on «${heading}»`, !!p && p.includes('<w:keepNext/>'),
    p ? 'paragraph found but has no keepNext' : 'heading not found');
}

// The inverse: ordinary body text must NOT be pinned, or a long section would
// be dragged onto the next page wholesale.
for (const notAHeading of ['Grid Synchronization & Net Metering', 'Inverter: 8 years']) {
  const p = leafParas.find(x => textOf(x) === notAHeading);
  check(`no keepNext on «${notAHeading}»`, !!p && !p.includes('<w:keepNext/>'),
    p ? 'pinned when it should flow' : 'paragraph not found');
}

// ── 3. Widow / orphan control ──────────────────────────────────────────────
const styles = part('word/styles.xml');
check('widow control not disabled', !styles.includes('<w:widowControl w:val="0"/>'), 'still off in docDefaults');
check('widow control enabled in docDefaults', styles.includes('<w:widowControl/>'), 'missing');

// ── 4. Tables ──────────────────────────────────────────────────────────────
const kids = children(doc);
const tables = kids.filter(k => k.name === 'w:tbl');
const roi = tables.find(t => t.xml.includes('ROI Analysis'));
const bom = tables.find(t => t.xml.includes('{{BOM_SL}}'));
const pricing = tables.find(t => t.xml.includes('{{WORK_DESC}}'));

check('ROI table found', !!roi, '');
check('BOM table found', !!bom, '');
check('pricing table found', !!pricing, '');

if (roi) {
  check('ROI table holds together', (roi.xml.match(/<w:keepNext\/>/g) || []).length > 0, 'no keepNext');
  check('ROI rows never split', (roi.xml.match(/<w:cantSplit\/>/g) || []).length === rowSpans(roi.xml).length,
    'not every row has cantSplit');
}
if (pricing) {
  check('pricing table holds together', (pricing.xml.match(/<w:keepNext\/>/g) || []).length > 0, 'no keepNext');
}
if (bom) {
  // The BOM must stay free to paginate — pinning it would break a long one.
  check('BOM is NOT pinned together', !bom.xml.includes('<w:keepNext/>'), 'has keepNext; a long BOM could not lay out');
  check('BOM rows never split', bom.xml.includes('<w:cantSplit/>'), 'no cantSplit');
  check('BOM header repeats per page', bom.xml.includes('<w:tblHeader/>'), 'no tblHeader');
  check('BOM has header + one token row', rowSpans(bom.xml).length === 2, `found ${rowSpans(bom.xml).length} rows`);
}

// ── 5. Footer ──────────────────────────────────────────────────────────────
for (const f of ['word/footer1.xml', 'word/footer2.xml']) {
  const x = part(f);
  const text = x.replace(/<[^>]+>/g, '');
  check(`${f}: pincode intact`, text.includes('Telangana India 500084'), 'not found');
  check(`${f}: no split pincode`, !text.includes('5 00084'), 'stray space still there');
  check(`${f}: company name centred`, !x.includes('<w:ind w:left="812"/>'), 'still indented 812');
}

// ── 6. Signatory follows the BOM ───────────────────────────────────────────
const lastTblEnd = doc.lastIndexOf('</w:tbl>');
const lastParaEnd = doc.lastIndexOf('</w:p>');
check('nothing paginates between the BOM and the signatory',
  !doc.slice(lastTblEnd, lastParaEnd).includes('<w:sectPr'), 'a section break survives there');
check('no trailing filler paragraphs',
  (doc.slice(lastTblEnd).match(/<w:p [^>]*>/g) || []).length < 14,
  `${(doc.slice(lastTblEnd).match(/<w:p [^>]*>/g) || []).length} paragraphs after the BOM`);

// ── Report ─────────────────────────────────────────────────────────────────
ok.forEach(l => console.log('  ok   ' + l));
failures.forEach(l => console.log('  FAIL ' + l));
console.log(`\n${ok.length} passed, ${failures.length} failed`);
process.exit(failures.length ? 1 : 0);
