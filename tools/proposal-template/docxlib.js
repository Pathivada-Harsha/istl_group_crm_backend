// Minimal WordprocessingML surgery helpers used to build the proposal skeleton.
// Everything works on raw XML strings so untouched regions stay byte-identical.

/** Split <w:body> into its top-level children (w:p / w:tbl / w:sectPr). */
function children(xml) {
  const s = xml.indexOf('<w:body>') + '<w:body>'.length;
  const e = xml.lastIndexOf('</w:body>');
  const body = xml.slice(s, e);
  const out = [];
  let i = 0;
  while (i < body.length) {
    if (body[i] !== '<') { i++; continue; }
    const close = body.indexOf('>', i);
    const raw = body.slice(i + 1, close);
    const name = raw.split(/[\s/]/)[0];
    if (raw.endsWith('/')) { out.push({ name, xml: body.slice(i, close + 1) }); i = close + 1; continue; }
    const end = matchEnd(body, i, name);
    out.push({ name, xml: body.slice(i, end) });
    i = end;
  }
  out.prefix = xml.slice(0, s);
  out.suffix = xml.slice(e);
  return out;
}

/** Index just past the </name> that closes the element starting at `start`. */
function matchEnd(s, start, name) {
  const openRe = new RegExp(`<${name}[\\s>]`, 'g');
  const closeTag = `</${name}>`;
  let depth = 0;
  let i = start;
  while (i < s.length) {
    openRe.lastIndex = i;
    const om = openRe.exec(s);
    const ci = s.indexOf(closeTag, i);
    if (ci === -1) return s.length;
    if (om && om.index < ci) {
      // Ignore self-closing opens (e.g. <w:p/>)
      const gt = s.indexOf('>', om.index);
      if (s[gt - 1] !== '/') depth++;
      i = gt + 1;
    } else {
      depth--;
      i = ci + closeTag.length;
      if (depth === 0) return i;
    }
  }
  return s.length;
}

/**
 * Spans of every <w:p>…</w:p> in `xml`, innermost first. Nesting-aware:
 * cover-page text boxes put real paragraphs *inside* a paragraph.
 */
function paragraphSpans(xml) {
  const re = /<w:p(\s[^>]*)?>|<\/w:p>/g;
  const stack = [];
  const spans = [];
  let m;
  while ((m = re.exec(xml)) !== null) {
    if (m[0] === '</w:p>') {
      const start = stack.pop();
      if (start !== undefined) spans.push({ start, end: m.index + '</w:p>'.length });
    } else if (!m[0].endsWith('/>')) {
      stack.push(m.index);
    }
  }
  return spans; // innermost-first by construction
}

const T_RE = /<w:t(\s[^>]*)?>([^<]*)<\/w:t>/g;

function decode(s) {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}
function encode(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Concatenated text of one paragraph's XML (includes nested paragraphs). */
function paraText(pXml) {
  let out = '';
  T_RE.lastIndex = 0;
  let m;
  while ((m = T_RE.exec(pXml)) !== null) out += decode(m[2]);
  return out;
}

/**
 * Rewrite a paragraph so all its text becomes `text`: the first <w:t> carries
 * the whole string, the rest are emptied. Keeps the first run's formatting,
 * which is what makes a multi-run label safely replaceable by a token.
 */
function setParaText(pXml, text) {
  let first = true;
  return pXml.replace(T_RE, () => {
    if (first) { first = false; return `<w:t xml:space="preserve">${encode(text)}</w:t>`; }
    return '<w:t xml:space="preserve"></w:t>';
  });
}

/**
 * Apply `map` (exact paragraph text -> replacement) to every paragraph in `xml`.
 * Returns { xml, hits } so the caller can assert every rule actually matched.
 */
function replaceParagraphs(xml, map) {
  const hits = {};
  Object.keys(map).forEach(k => { hits[k] = 0; });
  // Leaf paragraphs only. Text-box covers nest real paragraphs inside a
  // paragraph; editing the outer one after the inner ones would splice at a
  // stale offset, and every string we target lives in a leaf anyway.
  const spans = paragraphSpans(xml)
    .filter(sp => {
      const inner = xml.slice(xml.indexOf('>', sp.start) + 1, sp.end);
      return !/<w:p[\s>]/.test(inner);
    })
    .sort((a, b) => b.start - a.start);
  let out = xml;
  for (const sp of spans) {
    const seg = out.slice(sp.start, sp.end);
    const txt = paraText(seg);
    if (Object.prototype.hasOwnProperty.call(map, txt)) {
      hits[txt]++;
      out = out.slice(0, sp.start) + setParaText(seg, map[txt]) + out.slice(sp.end);
    }
  }
  return { xml: out, hits };
}

/** Append a plain run carrying `text` at the end of the paragraph `pXml`. */
function appendRun(pXml, text) {
  const i = pXml.lastIndexOf('</w:p>');
  return pXml.slice(0, i) + `<w:r><w:t xml:space="preserve">${encode(text)}</w:t></w:r>` + pXml.slice(i);
}

/** Byte offsets of each <w:tr>…</w:tr> directly inside a table's XML. */
function rowSpans(tblXml) {
  const re = /<w:tr(\s[^>]*)?>/g;
  const spans = [];
  let m;
  while ((m = re.exec(tblXml)) !== null) {
    if (m[0].endsWith('/>')) continue;
    const end = matchEnd(tblXml, m.index, 'w:tr');
    spans.push({ start: m.index, end });
    re.lastIndex = end;
  }
  return spans;
}

/** Byte offsets of each <w:tc>…</w:tc> directly inside a row's XML. */
function cellSpans(trXml) {
  const re = /<w:tc(\s[^>]*)?>/g;
  const spans = [];
  let m;
  while ((m = re.exec(trXml)) !== null) {
    if (m[0].endsWith('/>')) continue;
    const end = matchEnd(trXml, m.index, 'w:tc');
    spans.push({ start: m.index, end });
    re.lastIndex = end;
  }
  return spans;
}

module.exports = {
  children, matchEnd, paragraphSpans, paraText, setParaText,
  replaceParagraphs, appendRun, rowSpans, cellSpans, encode, decode,
};
