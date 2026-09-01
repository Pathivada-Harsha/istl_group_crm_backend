// Split document.xml into top-level body children and report index/type/text.
// Usage: node paras.js <document.xml> [--raw N] [--find text]
const fs = require('fs');
const xml = fs.readFileSync(process.argv[2], 'utf8');

const bodyStart = xml.indexOf('<w:body>') + '<w:body>'.length;
const bodyEnd = xml.lastIndexOf('</w:body>');
const body = xml.slice(bodyStart, bodyEnd);

// Walk top-level elements of body
const children = [];
let i = 0;
while (i < body.length) {
  if (body[i] !== '<') { i++; continue; }
  const close = body.indexOf('>', i);
  const raw = body.slice(i + 1, close);
  if (raw.startsWith('?') || raw.startsWith('!')) { i = close + 1; continue; }
  const name = raw.split(/[\s/]/)[0];
  if (raw.endsWith('/')) { children.push({ name, xml: body.slice(i, close + 1) }); i = close + 1; continue; }
  // find matching close
  let depth = 1, j = close + 1;
  while (depth > 0 && j < body.length) {
    const nx = body.indexOf('<' + name, j);
    const ncl = body.indexOf('</' + name + '>', j);
    if (ncl === -1) break;
    if (nx !== -1 && nx < ncl) {
      const gt = body.indexOf('>', nx);
      const seg = body.slice(nx + 1, gt);
      // ensure exact tag name match (not w:pPr matching w:p)
      const nm = seg.split(/[\s/]/)[0];
      if (nm === name && !seg.endsWith('/')) depth++;
      j = gt + 1;
    } else {
      depth--;
      j = ncl + ('</' + name + '>').length;
    }
  }
  children.push({ name, xml: body.slice(i, j) });
  i = j;
}

function textOf(s) {
  let out = '';
  const re = /<w:t(?:\s[^>]*)?>([^<]*)<\/w:t>/g;
  let m; while ((m = re.exec(s)) !== null) out += m[1];
  return out.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}
function runCount(s) { return (s.match(/<w:t(?:\s[^>]*)?>/g) || []).length; }

const rawIdx = process.argv.indexOf('--raw');
const findIdx = process.argv.indexOf('--find');

if (rawIdx > -1) {
  const n = parseInt(process.argv[rawIdx + 1], 10);
  console.log(children[n].xml);
  process.exit(0);
}
if (findIdx > -1) {
  const needle = process.argv[findIdx + 1];
  children.forEach((c, n) => {
    if (c.xml.includes(needle)) console.log(`#${n} <${c.name}> len=${c.xml.length} runs=${runCount(c.xml)} :: ${textOf(c.xml).slice(0, 300)}`);
  });
  process.exit(0);
}

children.forEach((c, n) => {
  const t = textOf(c.xml).replace(/\s+/g, ' ').trim();
  console.log(`#${n} <${c.name}> len=${c.xml.length} wt=${runCount(c.xml)} :: ${t.slice(0, 160)}`);
});
