# DRA / FTEN licensing and execution constraints

## Authoritative recheck — 2026-09-09 (Astra)

Both exact records were fetched successfully through BC's own catalogue
`package_show` API (the public HTML pages require JavaScript):

| Exact record | Catalogue ID | Licence | isopen |
|---|---|---|---|
| forest-tenure-road-section-lines | 243c94a1-f275-41dc-bc37-91d8a2b26e10 | Access Only (license_id 22) | false |
| digital-road-atlas-dra-master-partially-attributed-roads | bb060417-b6e6-4548-b837-f9060d94743e | Access Only (license_id 22) | false |

Primary evidence:
- https://catalogue.data.gov.bc.ca/api/3/action/package_show?id=forest-tenure-road-section-lines
- https://catalogue.data.gov.bc.ca/api/3/action/package_show?id=digital-road-atlas-dra-master-partially-attributed-roads
- Both license_url fields: https://www2.gov.bc.ca/gov/content?id=1AAACC9C65754E4D89A118B875E0FBDA
- This resolves to BC's Copyright policy: https://www2.gov.bc.ca/gov/content/home/copyright
  (page dated March 16, 2026; retrieved September 9, 2026).

The linked policy explicitly requires written permission for reproduction of
access-only catalogue material. No internal-crew or personal-GPX exception
was found in these published terms. This establishes the published licence
status, not whether Dale's employer already holds separate permission.

For the intended workflow:
- Viewing records to identify a road is distinct from making copies.
- A GPX containing copied/derived road coordinates for crew phones is still
  reproduction; keeping it internal does not establish permission under these terms.
- Public derived geometry and rendered maps likewise have no permission established.
- Honour Dale's licence gate: do not run live geometry extraction or produce
  a GPX until applicable written permission or an independently verified
  suitable licensed source is available. No live locator run occurred here.
- Ask whether the employer already has permission covering these exact layers
  and crew-phone derivatives, or use the policy's Copyright Permission Request
  Form to request that scope. No request has been sent.

The sibling FTEN records and third-party attribution do not overturn these
exact-record findings. The earlier Access Only report was correct. That is
not a claim that every FTEN layer is restricted.

### Code review corrections and limits

Claude's statement that discover_roads.py downloads no geometry was incorrect:
its GetFeature request has no propertyName exclusion, so the default response
can include geometry even though report() only prints attributes. Its header
has been corrected; do not treat it as an attribute-only licensing workaround.
The locator likewise fetches geometry even though it writes no output files.

The locator's closest_approach checks candidate vertices against road segments,
not exact segment-to-segment distance; a crossing between candidate vertices
can be missed. Greedy chaining is heuristic and not proof of a unique road;
increasing tolerance can connect unrelated roads. Both-end chain kilometres
are uncalibrated, not posted kilometres, and Albers distance is projected distance.
The count-cap warning does not establish completeness; server-side caps can
be lower than the requested count. Pagination/schema verification remain pending.
The bbox helper uses corners only; its enclosure claim is not general for
boxes crossing the central meridian. Current corridor boxes are east of it.

The seven stored projection-reference checks passed again (worst 0.000049 m).
This compares against Claude's rounded constants, not a fresh independent
pyproj comparison or real-world GPS accuracy. Projection code does not perform
a WGS84-to-NAD83 datum transformation. The synthetic locator tests described
by Claude were not attached and have not been independently rerun here.
`python -S locate_c_road.py --help` passed without site packages.

### Windows execution decision

Use stdlib discovery/projection tooling with `py` or the full Python312 path,
subject to the licence gate above. No pip installation is needed for those
scripts. MBTiles rendering and PNG validation run elsewhere on a permitted
Linux workstation/runtime: Shapely AND Pillow load native extensions.
The renderer has not been converted to pure Python. Its dependencies are
isolated in requirements-render.txt; requirements.txt is dependency-free.
Do not reinstall DLL packages or change office Application Control to run it.

---

## Original Claude notes (historical; findings above supersede uncertainty)

# DRA / FTEN licensing: unresolved, and environment constraints

Added 2026-09-09 by a Claude session, continuing after Astra. Two things a
future agent needs before touching provincial road data or writing more
tooling.

## 1. The DRA/FTEN licence question is disputed, not settled

`docs/road-maps/README.md` states that DRA and FTEN were investigated and
excluded because "the catalogue API returned `Access Only` and a BC terms
URL for both records", and that an earlier conversational recommendation
overstated their open-data status.

That warning was the right call at the time: do not ship derived geometry
on an assumption. But the evidence now points both ways, so **treat this as
an open question, not a closed door.**

Evidence that the FTEN road-line family is Open Government Licence - British
Columbia:

- The federal Open Government Portal, which harvests metadata directly from
  BC's catalogue, lists `Licence: Open Government Licence - British Columbia`
  for **Forest Tenure Road Segment Lines** (record 9e5bfa62-2339-445e-bf67-81657180c682)
  and for **Forest Tenure Road Section Amendment** (record bad79704-3fc9-43e7-b5d4-660657199af8).
- A third-party deployment of an FTEN roads layer (Skeena Knowledge Trust)
  carries the standard OGL-BC attribution text, which implies redistribution
  under that licence.

What is genuinely NOT confirmed:

- No source seen so far states the licence for `FTEN_ROAD_SECTION_LINES_SVW`
  **specifically** - the exact layer this toolchain would query. The
  confirmations above are for the sibling *Segment* Lines and *Section
  Amendment* records. These are related but distinct catalogue entries and
  can carry different terms.
- The DRA record's terms were not independently re-checked in this session.

**How to actually resolve it** (five minutes, do this before extracting):

1. Open https://catalogue.data.gov.bc.ca/dataset/forest-tenure-road-section-lines
   and read the Licence field on the record itself. The catalogue record is
   authoritative; federal mirrors and third-party deployments are not.
2. Same for https://catalogue.data.gov.bc.ca/dataset/digital-road-atlas-dra-master-partially-attributed-roads
3. If either says "Access Only" or links to bespoke terms, read those terms.
   Access to a public WFS endpoint is not permission to redistribute.
4. Record the finding here with the date, and correct
   `docs/road-maps/README.md` in the same commit either way.

Note the distinction that matters for Dale's actual use: **using** the data
to identify a road and produce a GPX track for his own crew's phones is a
different question from **redistributing** rendered maps or committing
derived geometry to a public repository. Confirm the terms for the use you
actually intend rather than the broadest one.

If the terms do turn out to be restrictive, OSM remains the fallback Astra
already used - with the known and documented limitation that OSM's forestry
spur coverage in the Cariboo is thin, which is the exact problem this was
meant to solve.

## 2. Windows Application Control blocks compiled Python packages

On Dale's office Windows machine, `pyproj` fails at import:

```
ImportError: DLL load failed while importing _sync:
An Application Control policy has blocked this file.
```

This is a machine security policy. Reinstalling does not fix it, and
`pip install` reports success because the install genuinely worked - only
loading the DLL is blocked. **shapely has the same exposure** and should be
assumed blocked until proven otherwise on that machine.

Consequences for this toolchain:

- `tools/road-maps/requirements.txt` currently pins shapely; the existing
  renderer will not run on that machine as-is.
- Anything that only needs a coordinate transform should import
  `bc_albers.py` (added here, pure Python, no binary dependency).
- Segment stitching and length calculation can also be done with stdlib
  math. Prefer that over shapely for any new road-extraction code.

Also on that machine: `python3` resolves to the Microsoft Store alias at
`%LOCALAPPDATA%\Microsoft\WindowsApps\python3.exe`, while `pip` installs into
the real interpreter at `%LOCALAPPDATA%\Programs\Python\Python312\`. Packages
install successfully and then appear missing. Use `py script.py`, or the full
path to `Python312\python.exe`. Disabling the Store aliases under
Settings -> Apps -> Advanced app settings -> App execution aliases avoids the
whole class of problem.

## 3. What was added here

- `bc_albers.py` - EPSG:3005 forward projection, pure Python. Verified
  against pyproj 3.8.0 at seven reference points spanning BC; worst
  disagreement 0.000049 m. Run `python bc_albers.py` to re-verify.
- `discover_roads.py` - read-only WFS attribute inspector, stdlib only.
  Prints what attributes exist and their distinct values so a road can be
  identified by its real filter value rather than a guessed field name.
  Writes nothing and downloads no geometry, so it is safe to run while the
  licence question above is still open.

Neither has been run against the live BC endpoint. The session that wrote
them had no network route to `openmaps.gov.bc.ca`; error paths were
exercised, the success path was not. First real execution is on Dale's
machine, and the field-name guesses in the output may need correcting once
the true attribute list is visible.
