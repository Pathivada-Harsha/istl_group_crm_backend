/*
 * Builds the solar-proposal skeleton from the RESIDENTIAL reference .docx.
 *
 * Everything the proposal keeps identical (cover art, boilerplate, images,
 * header, footer band, signatory) is carried over byte-for-byte; only the
 * variable regions are swapped for {{TOKEN}}s and the two optional sections
 * are wrapped in {{#BLOCK}}…{{/BLOCK}} markers the filler can cut out.
 *
 * Usage: node buildTemplate.js <ref.docx> <out.docx>
 */
const fs = require('fs');
const { readZip, writeZip } = require('./zip');
const {
  children, replaceParagraphs, appendRun, rowSpans, cellSpans, paragraphSpans, paraText,
} = require('./docxlib');

const [, , REF, OUT] = process.argv;

const entries = readZip(fs.readFileSync(REF));
const get = (name) => entries.find(e => e.name === name);
const put = (name, str) => { get(name).data = Buffer.from(str, 'utf8'); };

const xml = get('word/document.xml').data.toString('utf8');
const kids = children(xml);

const problems = [];
function edit(idx, map) {
  const { xml: out, hits } = replaceParagraphs(kids[idx].xml, map);
  Object.entries(hits).forEach(([k, n]) => {
    if (n === 0) problems.push(`child ${idx}: no paragraph matched «${k}»`);
  });
  kids[idx].xml = out;
}

/** True for a spacer paragraph — no text, no picture, just vertical space. */
function isSpacer(pXml) {
  const hasText = /<w:t(?:\s[^>]*)?>[^<]/.test(pXml);
  const hasDrawing = pXml.includes('<w:drawing') || pXml.includes('<w:pict');
  return !hasText && !hasDrawing;
}

/** Add w:keepNext to one paragraph, creating a pPr if it has none. */
function addKeepNext(pXml) {
  if (pXml.includes('<w:keepNext/>')) return pXml;
  if (pXml.includes('<w:pPr>')) return pXml.replace('<w:pPr>', '<w:pPr><w:keepNext/>');
  return pXml.replace(/^(<w:p(?:\s[^>]*)?>)/, '$1<w:pPr><w:keepNext/></w:pPr>');
}

/**
 * Bind a heading to the block it introduces so it is never left alone at the
 * foot of a page. The chain continues through any spacer paragraphs between
 * them — keepNext only reaches the *next* paragraph, so a blank line in between
 * would otherwise be all the heading holds on to.
 *
 * `expect` is the heading's own text; the build fails if it has moved, because
 * these are positional indices into the reference.
 */
function keepWithNext(idx, expect) {
  const actual = paraText(kids[idx].xml).replace(/\s+/g, ' ').trim();
  if (!actual.startsWith(expect)) {
    problems.push(`child ${idx}: expected heading «${expect}», found «${actual.slice(0, 60)}»`);
    return;
  }
  kids[idx].xml = addKeepNext(kids[idx].xml);
  for (let i = idx + 1; i < kids.length && isSpacer(kids[i].xml) && kids[i].name === 'w:p'; i++) {
    kids[i].xml = addKeepNext(kids[i].xml);
  }
}

/**
 * Hold a short table on one page. Every paragraph except those in the final row
 * gets keepNext, so each row keeps hold of the one below it; cantSplit stops a
 * single row being torn in half. Only for tables of a known, small size — the
 * BOM must stay free to flow or a long one could not be laid out at all.
 */
function keepTableTogether(idx) {
  let t = kids[idx].xml;
  const rows = rowSpans(t);
  if (!rows.length) { problems.push(`child ${idx}: expected a table with rows`); return; }

  // Back to front so the offsets of the rows still to do stay valid.
  for (let i = rows.length - 1; i >= 0; i--) {
    let row = t.slice(rows[i].start, rows[i].end);

    // The last row has nothing below it to hold on to.
    if (i < rows.length - 1) {
      for (const sp of paragraphSpans(row).sort((a, b) => b.start - a.start)) {
        row = row.slice(0, sp.start) + addKeepNext(row.slice(sp.start, sp.end)) + row.slice(sp.end);
      }
    }
    row = row.includes('<w:trPr>')
      ? row.replace('<w:trPr>', '<w:trPr><w:cantSplit/>')
      : row.replace(/^(<w:tr(?:\s[^>]*)?>)/, '$1<w:trPr><w:cantSplit/></w:trPr>');

    t = t.slice(0, rows[i].start) + row + t.slice(rows[i].end);
  }
  kids[idx].xml = t;
}

// ── Cover page (text appears twice: DrawingML choice + VML fallback) ────────
edit(3, {
  'Ashok Erugurala Residential': '{{COVER_TITLE}}',
  '| 4 kWp  Ongrid Rooftop Solar Plant |': '| {{COVER_SUBTITLE}} |',
});

// ── Header info table — value cells rebuilt so any length stays centred ─────
{
  const t = kids[5].xml;
  const tokens = ['{{CLIENT_NAME}}', '{{SITE_LOCATION}}', '{{CAPACITY_LINE}}'];
  const cell = (tok) =>
    '<w:p><w:pPr><w:pStyle w:val="TableParagraph"/><w:spacing w:before="135"/>' +
    '<w:ind w:left="38" w:right="38"/><w:rPr><w:b/><w:sz w:val="24"/><w:szCs w:val="10"/></w:rPr></w:pPr>' +
    '<w:r><w:rPr><w:b/><w:spacing w:val="15"/><w:sz w:val="24"/><w:szCs w:val="10"/></w:rPr>' +
    `<w:t xml:space="preserve">${tok}</w:t></w:r></w:p>`;
  let out = t;
  // Back-to-front so earlier offsets stay valid.
  const rows = rowSpans(t);
  if (rows.length !== 3) problems.push(`header info table: expected 3 rows, got ${rows.length}`);
  for (let i = rows.length - 1; i >= 0; i--) {
    const rx = t.slice(rows[i].start, rows[i].end);
    const cs = cellSpans(rx);
    const valueCell = rx.slice(cs[1].start, cs[1].end);
    const pStart = valueCell.indexOf('<w:p ');
    const pEnd = valueCell.lastIndexOf('</w:p>') + '</w:p>'.length;
    const rebuilt = valueCell.slice(0, pStart) + cell(tokens[i]) + valueCell.slice(pEnd);
    const absStart = rows[i].start + cs[1].start;
    const absEnd = rows[i].start + cs[1].end;
    out = out.slice(0, absStart) + rebuilt + out.slice(absEnd);
  }
  kids[5].xml = out;
}

// ── Financial pricing table ────────────────────────────────────────────────
edit(78, {
  'Price for 4kWp Plant': 'Price for {{CAPACITY_LABEL}} Plant',
  ' 4kWp Rooftop Solar PV Plant with DCR panels': '{{WORK_DESC}}',
  '₹2,32,747': '{{PRICE_BASE}}',
  '8.9%': '{{GST_PCT}}',
  '₹20,714.4': '{{GST_AMOUNT}}',
  '₹2,53,461': '{{PRICE_TOTAL}}',
});

// ── Amount in words ────────────────────────────────────────────────────────
edit(80, {
  'Amount in Words: Rupees Two Lakhs Fifty Three Thousand Four Hundred Sixty One Only.':
    'Amount in Words: {{AMOUNT_WORDS}}',
});

// ── Subsidy block: children 81…86 (spacer + heading + 4 bullets) ───────────
kids[81].xml = appendRun(kids[81].xml, '{{#SUBSIDY}}');
edit(83, { 'Central Government Subsidy Applicable: ₹78,000 ': '{{SUBSIDY_LINE}}' });
edit(84, {
  "Subsidy will be credited directly to the customer's bank account as per MNRE norms.":
    '{{SUBSIDY_NOTE_1}}',
});
edit(85, {
  'Subsidy is subject to approval from the concerned authorities and compliance with prevailing MNRE guidelines.':
    '{{SUBSIDY_NOTE_2}}',
});
edit(86, {
  'Customer has to register on the National Portal for Rooftop Solar and complete all required formalities for subsidy claim.':
    '{{SUBSIDY_NOTE_3}}',
});
kids[86].xml = appendRun(kids[86].xml, '{{/SUBSIDY}}');

// ── ROI block: children 88…92 (spacers + table + the spacer after it).
// The closing marker sits on 92 rather than 91 because 91 is one of the hard
// page breaks removed below.
kids[88].xml = appendRun(kids[88].xml, '{{#ROI}}');
edit(90, {
  'ROI Analysis – 4kWp Solar System ': 'ROI Analysis – {{ROI_TITLE_CAP}} Solar System',
  '4kWp': '{{ROI_CAPACITY}}',
  '₹ 2,32,747': '{{PRICE_BASE}}',
  'GST @ 8.9%': 'GST @ {{GST_PCT}}',
  '₹20,714': '{{GST_AMOUNT_ROUNDED}}',
  '₹2,53,461': '{{PRICE_TOTAL}}',
  '₹9.25 / Unit': '{{ROI_TARIFF}}',
  '1,460 Units/kWp/Year': '{{ROI_SPECIFIC_GEN}}',
  '5,840 Units': '{{ROI_ANNUAL_GEN}}',
  '₹54,020': '{{ROI_ANNUAL_SAVINGS}}',
  '₹4,502': '{{ROI_MONTHLY_SAVINGS}}',
  '4.6 Years': '{{ROI_PAYBACK}}',
  '21.31%': '{{ROI_ANNUAL_ROI}}',
  '25+ Years': '{{ROI_LIFE}}',
  '1,46,000Units': '{{ROI_LIFETIME_GEN}}',
  '₹13,50,500': '{{ROI_LIFETIME_SAVINGS}}',
  '₹10,97,039': '{{ROI_NET_BENEFIT}}',
});
kids[92].xml = appendRun(kids[92].xml, '{{/ROI}}');

// ── Note block: quote validity ─────────────────────────────────────────────
edit(95, {
  'Quote valid for 10 days from Wednesday (29-07-26). ':
    'Quote valid for {{QUOTE_VALID_DAYS}} days from {{QUOTE_VALID_DAY}} ({{QUOTE_VALID_DATE}}).',
});

// ── Bill of Material: header row + ONE repeatable token row ────────────────
{
  const t = kids[108].xml;
  const rows = rowSpans(t);
  if (rows.length !== 15) problems.push(`BOM table: expected 15 rows, got ${rows.length}`);

  // Header row repeats on every page the table flows onto, and never splits.
  let header = t.slice(rows[0].start, rows[0].end);
  header = header.replace('<w:trPr>', '<w:trPr><w:tblHeader/><w:cantSplit/>');

  // Data row template: keep each cell's width, normalise the paragraph.
  const row1 = t.slice(rows[1].start, rows[1].end);
  const cs = cellSpans(row1);
  const TOKENS = ['{{BOM_SL}}', '{{BOM_COMPONENT}}', '{{BOM_SPEC}}', '{{BOM_MAKE}}', '{{BOM_QTY}}', '{{BOM_UNIT}}'];
  if (cs.length !== TOKENS.length) problems.push(`BOM row: expected 6 cells, got ${cs.length}`);
  const cells = cs.map((c, i) => {
    const cell = row1.slice(c.start, c.end);
    const tcPr = /<w:tcPr>.*?<\/w:tcPr>/s.exec(cell)[0];
    // The Specifications column is long free text — left-align it like the reference.
    const align = i === 2 ? '<w:jc w:val="left"/>' : '';
    const ind = i === 2 ? '<w:ind w:left="91" w:right="84"/>' : '<w:ind w:left="8" w:right="8"/>';
    return `<w:tc>${tcPr}<w:p><w:pPr><w:pStyle w:val="TableParagraph"/>` +
      `<w:spacing w:before="80" w:after="80"/>${ind}${align}<w:rPr><w:sz w:val="28"/></w:rPr></w:pPr>` +
      `<w:r><w:rPr><w:sz w:val="28"/></w:rPr><w:t xml:space="preserve">${TOKENS[i]}</w:t></w:r></w:p></w:tc>`;
  });
  // trHeight is a MINIMUM in Word, so a small one lets long specs grow the row;
  // cantSplit keeps a row whole rather than tearing it across a page boundary.
  const tplRow = '<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val="402"/></w:trPr>' + cells.join('') + '</w:tr>';

  kids[108].xml = t.slice(0, rows[0].start) + header + tplRow + t.slice(rows[rows.length - 1].end);
}

// The reference splits the BOM over two hand-made tables; one flowing table
// with a repeating header renders the same and takes any number of rows.
kids[110].drop = true;
kids[111].drop = true;

// ── Let the document flow ──────────────────────────────────────────────────
// The reference pins its pagination with hard section breaks. That works for a
// document whose content never changes, but a BOM that is 8 rows on one lead
// and 30 on the next makes them wrong every time: a page that ends early stays
// half empty, and whatever follows a grown table still starts on a fresh page.
//
// Six of these breaks (39, 67, 73, 87, 91, 105) change *nothing* — same page
// size, same margins, no header or footer of their own — so they are pure
// forced page breaks and simply go. 109 also switches to header2/footer2, but
// header2 is the same logo and footer2 is footer1 minus its divider rule, so
// dropping it costs nothing and lets the signatory sit under the BOM.
//
// The break at 6 stays: it is `continuous` (no page break at all) and is what
// installs the running header, the footer band and the page margins.
[39, 67, 73, 87, 91, 105, 109].forEach(i => {
  if (!kids[i].xml.includes('<w:sectPr')) problems.push(`child ${i}: expected a section break`);
  kids[i].drop = true;
});

// Section 6 governed everything up to 109; with 109 gone the body-level sectPr
// governs the whole document after the cover, so it has to carry the same page
// setup or the margins would shift mid-document.
{
  const cover = /<w:sectPr[^>]*>([\s\S]*?)<\/w:sectPr>/.exec(kids[6].xml);
  if (!cover) problems.push('child 6: could not read the cover section properties');
  else {
    const body = cover[1]
      .replace(/<w:type w:val="continuous"\/>/, '')   // the body section is the last one
      .replace(/<w:pgNumType[^>]*\/>/, '');
    kids[140].xml = `<w:sectPr w:rsidR="00EA4899">${body}</w:sectPr>`;
  }
}

// Trailing filler: the reference pads the signatory page with 17 empty
// paragraphs and a divider rule to push that rule to the foot of a page it
// knew the length of. Flowing content makes both a gap. footer1 carries the
// same rule on every page, so nothing is lost.
for (let i = 123; i <= 139; i++) kids[i].drop = true;

// ── Nothing may be stranded at a page break ────────────────────────────────
// Every heading and every "…:" lead-in is bound to the block it introduces, so
// a section can never leave its title alone at the foot of one page with the
// body on the next. Text is asserted because these are positional indices.
[
  [9,   'Our Company Overview'],
  [18,  'Our Expertise'],
  [20,  'Sesola has developed strong expertise'],
  [31,  'EPC Capabilities'],
  [33,  'Sesola offers complete turnkey EPC services'],
  [41,  'Innovation & R&D'],
  [43,  'Sesola Energy emphasizes continuous innovation'],
  [50,  'Global Presence:'],
  [52,  'Headquartered in Hyderabad'],
  [57,  'Safety & Quality Commitment:'],
  [60,  'Our safety practices include:'],
  [69,  'Our Sample PROJECT EXECUTION'],
  [71,  'About the System'],
  [74,  'How Net Metering Works'],
  [76,  'Financial Pricing'],
  [82,  'Subsidy Details:-'],
  [93,  'Note:'],
  [101, 'Warranty:'],
  [106, 'Bill of Material'],
].forEach(([idx, expect]) => keepWithNext(idx, expect));

// Short fixed-size tables stay whole rather than splitting across a page.
// The BOM is deliberately excluded — it has to be free to flow.
[5, 78, 90].forEach(keepTableTogether);

if (problems.length) {
  console.error('TEMPLATE BUILD PROBLEMS:\n  ' + problems.join('\n  '));
  process.exit(1);
}

const rebuilt = kids.prefix + kids.filter(k => !k.drop).map(k => k.xml).join('') + kids.suffix;
put('word/document.xml', rebuilt);

// ── Document properties ────────────────────────────────────────────────────
put('docProps/core.xml', get('docProps/core.xml').data.toString('utf8')
  .replace(/<dc:title>[^<]*<\/dc:title>/, '<dc:title>{{DOC_TITLE}}</dc:title>')
  .replace(/<dc:creator>[^<]*<\/dc:creator>/, '<dc:creator>Sesola Energy CRM</dc:creator>')
  .replace(/<cp:lastModifiedBy>[^<]*<\/cp:lastModifiedBy>/, '<cp:lastModifiedBy>Sesola Energy CRM</cp:lastModifiedBy>'));

put('docProps/app.xml', get('docProps/app.xml').data.toString('utf8')
  .replace(/<vt:lpstr>[^<]*<\/vt:lpstr>/, '<vt:lpstr>{{DOC_TITLE}}</vt:lpstr>'));

// ── Widow / orphan control ─────────────────────────────────────────────────
// The reference turns it OFF for the whole document (`w:widowControl w:val="0"`
// in docDefaults — the usual souvenir of a PDF-to-Word conversion), which is
// what lets Word strand a single line of a paragraph on its own at a page
// boundary. 49 paragraphs already override it back on, so the document was
// half-fixed by hand; switching the default on makes it uniform.
{
  const stylesName = 'word/styles.xml';
  const styles = get(stylesName).data.toString('utf8');
  if (!styles.includes('<w:widowControl w:val="0"/>')) {
    problems.push('styles.xml: expected widowControl to be disabled in docDefaults');
  }
  put(stylesName, styles.replace('<w:widowControl w:val="0"/>', '<w:widowControl/>'));
}

// ── Footer band ────────────────────────────────────────────────────────────
// Two defects carried over from the reference: the pincode is typed with a
// space inside it ("Telangana India 5 00084"), and the company-name line is
// indented 812 twips while the two address lines under it are centred, so the
// block reads crooked. Both live in a text box that is duplicated into the VML
// fallback, so every copy is fixed.
{
  const ADDRESS_BAD = '8th Floor, Pranava Vaishnoi Business Park, Kothaguda Village, '
    + 'Serilingampally Mandal, Ranga Reddy District, Telangana India 5 00084';
  const ADDRESS_FIXED = '8th Floor, Pranava Vaishnoi Business Park, Kothaguda Village, '
    + 'Serilingampally Mandal, Ranga Reddy District, Telangana India 500084';
  const COMPANY = 'SESOLA POWER PROJECTS PRIVATE LIMITED';

  for (const part of ['word/footer1.xml', 'word/footer2.xml']) {
    let f = get(part).data.toString('utf8');

    const { xml: withAddress, hits } = replaceParagraphs(f, { [ADDRESS_BAD]: ADDRESS_FIXED });
    if (!hits[ADDRESS_BAD]) problems.push(`${part}: address line not found`);
    f = withAddress;

    // Centre the company name over the address lines it sits above.
    const spans = paragraphSpans(f)
      .filter(sp => {
        const seg = f.slice(sp.start, sp.end);
        return !/<w:p[\s>]/.test(seg.slice(seg.indexOf('>') + 1)) && paraText(seg) === COMPANY;
      })
      .sort((a, b) => b.start - a.start);
    if (!spans.length) problems.push(`${part}: company-name line not found`);
    for (const sp of spans) {
      const seg = f.slice(sp.start, sp.end);
      const fixed = seg.replace(/<w:ind w:left="812"\/>/, '<w:ind w:left="5" w:right="3"/><w:jc w:val="center"/>');
      f = f.slice(0, sp.start) + fixed + f.slice(sp.end);
    }
    put(part, f);
  }
}

// ── De-duplicate media ─────────────────────────────────────────────────────
// The cover art is stored twice: once for the DrawingML shape and once for its
// VML fallback. Every generated proposal is persisted, so ~2.7 MB of duplicate
// PNG per stored version is worth removing. Lossless — identical bytes only.
{
  const crypto = require('crypto');
  const byHash = new Map();
  const alias = new Map();          // duplicate part name -> canonical part name
  for (const e of entries) {
    if (!e.name.startsWith('word/media/')) continue;
    const h = crypto.createHash('sha1').update(e.data).digest('hex');
    if (byHash.has(h)) alias.set(e.name, byHash.get(h));
    else byHash.set(h, e.name);
  }
  if (alias.size) {
    const relsName = 'word/_rels/document.xml.rels';
    let rels = get(relsName).data.toString('utf8');
    for (const [dup, canon] of alias) {
      rels = rels.split(`Target="${dup.replace('word/', '')}"`).join(`Target="${canon.replace('word/', '')}"`);
    }
    put(relsName, rels);
    for (const name of alias.keys()) {
      entries.splice(entries.findIndex(e => e.name === name), 1);
    }
    console.log(`Deduplicated media: ${[...alias.keys()].join(', ')}`);
  }
}

fs.writeFileSync(OUT, writeZip(entries));

const tokens = [...new Set(rebuilt.match(/\{\{[#/]?[A-Z_0-9]+\}\}/g) || [])].sort();
console.log(`Wrote ${OUT} (${(fs.statSync(OUT).size / 1048576).toFixed(2)} MB)`);
console.log('Tokens: ' + tokens.join(' '));
