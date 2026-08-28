// Crude but effective DOCX document.xml -> readable outline dumper.
// Usage: node dumpdoc.js <path-to-document.xml>
const fs = require('fs');
const xml = fs.readFileSync(process.argv[2], 'utf8');

const tokens = [];
const re = /<([^>]+)>([^<]*)/g;
let m;
while ((m = re.exec(xml)) !== null) tokens.push({ raw: m[1], text: m[2] });

function decode(s) {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}

const out = [];
let para = [];
let inCell = false;
let cellParts = [];
let rowCells = [];
let tblDepth = 0;

for (const t of tokens) {
  let raw = t.raw;
  const closing = raw.startsWith('/');
  const selfClose = raw.endsWith('/');
  const name = raw.replace(/^\//, '').split(/[\s/]/)[0];

  if (name === 'w:tbl' && !closing && !selfClose) { tblDepth++; out.push(`\n@@TABLE-START (depth ${tblDepth})@@`); }
  else if (name === 'w:tbl' && closing) { out.push(`@@TABLE-END@@\n`); tblDepth--; }
  else if (name === 'w:tc' && !closing && !selfClose) { inCell = true; cellParts = []; }
  else if (name === 'w:tc' && closing) { inCell = false; rowCells.push(cellParts.join(' / ').trim()); }
  else if (name === 'w:tr' && closing) { out.push('ROW| ' + rowCells.join(' || ')); rowCells = []; }
  else if (name === 'w:p' && closing) {
    const s = para.join('').replace(/[ \t]+/g, ' ').trim();
    if (inCell) { cellParts.push(s); }
    else out.push(s);
    para = [];
  }
  else if (name === 'w:p' && selfClose) {
    if (inCell) cellParts.push(''); else out.push('');
  }
  else if (name === 'w:br') para.push(' \\n ');
  else if (name === 'w:tab') para.push('\\t');
  else if (name === 'w:pStyle') { const v = /w:val="([^"]+)"/.exec(raw); if (v) para.push(`{${v[1]}}`); }
  else if (name === 'a:blip' || name === 'v:imagedata') { const v = /r:(?:embed|id)="([^"]+)"/.exec(raw); para.push(`[[IMG ${v ? v[1] : '?'}]]`); }
  else if (name === 'wp:extent') { const cx = /cx="(\d+)"/.exec(raw), cy = /cy="(\d+)"/.exec(raw); if (cx) para.push(`(${(cx[1]/914400).toFixed(2)}x${(cy[1]/914400).toFixed(2)}in)`); }
  else if (name === 'w:sectPr' && !closing) out.push('\n===== SECTION PROPERTIES =====');
  else if (name === 'w:headerReference' || name === 'w:footerReference' || name === 'w:pgSz' || name === 'w:pgMar' || name === 'w:titlePg' || name === 'w:cols') out.push('   <' + raw + '>');
  else if (name === 'w:bookmarkStart') { const v = /w:name="([^"]+)"/.exec(raw); if (v && v[1] !== '_GoBack') para.push(`[bm:${v[1]}]`); }

  if (name === 'w:t' && !closing && t.text) para.push(decode(t.text));
}

console.log(out.join('\n').replace(/\n{3,}/g, '\n\n'));
