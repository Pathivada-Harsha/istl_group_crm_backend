# Solar proposal skeleton builder

Rebuilds `src/main/resources/proposal-templates/solar-proposal-template.docx`
from the signed-off reference proposal. Run it when sales changes the boilerplate,
the cover art, the warranty wording or the signatory — **never hand-edit the
skeleton in Word**, because the `{{TOKEN}}` placeholders must each stay inside a
single `<w:t>` run and Word will happily split them again.

```bash
cd Istl_group_crm_backend/tools/proposal-template
node buildTemplate.js "<path to the reference .docx>" ../../src/main/resources/proposal-templates/solar-proposal-template.docx
```

Node 18+; no npm install (the zip and WordprocessingML helpers are local).

## What it does

The reference is a real proposal, so everything except the client-specific
regions is already correct. The builder copies the package through untouched and
only:

1. replaces each variable label with a `{{TOKEN}}` (collapsing the paragraph's
   runs so the token survives Word's run splitting);
2. wraps the Subsidy block and the ROI block in `{{#SUBSIDY}}…{{/SUBSIDY}}` /
   `{{#ROI}}…{{/ROI}}` markers, including the spacer paragraph and the section
   break that ends the ROI page — dropping the block must not leave a blank page;
3. collapses the Bill of Material's two hand-made tables into one table with a
   repeating header row and a single token row the filler clones;
4. de-duplicates the cover art (it is stored twice, once for the DrawingML shape
   and once for its VML fallback — ~2.7 MB per stored document);
5. **removes the reference's hard pagination.** The reference pins its page
   boundaries with seven `nextPage` section breaks. Six of them change nothing at
   all (same page size, same margins, no header or footer of their own) and the
   seventh only swaps to `header2`/`footer2` — the same logo and footer1 minus its
   divider rule. For a document whose BOM is 8 rows on one lead and 30 on the next
   those breaks are wrong every time: a page ends half empty and whatever follows a
   grown table still starts on a fresh sheet. They go, the body-level `sectPr`
   inherits the cover section's page setup so margins do not shift mid-document,
   the 17 filler paragraphs padding the signatory page go with them, and the
   headings that introduce a figure or a table get `keepNext`. BOM rows get
   `cantSplit` so a row grows rather than tearing across a page boundary.
   The one surviving break (child 6) is `continuous` — it paginates nothing and is
   what installs the running header, the footer band and the margins.
6. fixes two defects the reference carried in its footer: the pincode typed with a
   space inside it (`Telangana India 5 00084`) and the company-name line indented
   812 twips while the address lines below it are centred;
7. **binds every heading to the block it introduces** (`keepNext`, chained through
   any spacer paragraph in between) so a section title is never stranded at the
   foot of a page with its body on the next, and **turns widow/orphan control back
   on** — the reference disables it document-wide in `docDefaults`
   (`w:widowControl w:val="0"`, the usual PDF-conversion souvenir), which is what
   lets Word leave a single line of a paragraph alone at a page boundary. Short
   fixed-size tables (header info, pricing, ROI) are held whole; the BOM is
   deliberately left free to paginate.

The builder **fails loudly** if any expected label is missing, which is what
catches a reference whose wording moved.

> The running backend caches the skeleton in memory on first use
> (`SolarProposalDocService.template()`), so **restart the API** after rebuilding it.

Then check it:

```bash
node verifyTemplate.js          # 41 assertions on the built skeleton
```

`verifyTemplate.js` asserts what the layout depends on — no forced page breaks,
every heading bound to its block, widow control on, short tables held whole, the
BOM free to flow, the footer corrected. It duplicates the layout half of
`SolarProposalDocTest` but needs no JVM, which matters on a machine that has no
memory left to fork one.

The Java side that fills it is
`service/docx/DocxTemplate.java` + `service/SolarProposalDocService.java`, and
`src/test/.../SolarProposalDocTest.java` re-renders both reference proposals from
the skeleton and asserts the boilerplate is unchanged.

## Inspecting a .docx

```bash
node dumpdoc.js <unzipped>/word/document.xml     # readable outline: text, tables, images, section breaks
node paras.js   <unzipped>/word/document.xml     # top-level body children with indices
node paras.js   <unzipped>/word/document.xml --raw 108      # one child's raw XML
node paras.js   <unzipped>/word/document.xml --find "text"  # which children contain a string
```

`buildTemplate.js` addresses paragraphs by **child index**, so if the reference
gains or loses a paragraph those indices move — use `paras.js` to find the new
ones and update the `edit(...)` calls.
