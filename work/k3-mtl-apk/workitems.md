# Work Items

| ID | title | role | targets | surface | status | evidence | notes |
|----|-------|------|---------|---------|--------|----------|-------|
| WI-001 | Establish scope and auth | lead | case | process | completed | scope.md | written authorization, authorized_target_only |
| WI-002 | Triage public APK and recover managed assembly | lead | public APK | static | completed | E-001, E-002 | v2.6.0, .NET MAUI DLL/PDB |
| WI-003 | Reconstruct OCR, Gemini, matching, inpainting, rendering | lead | K3 services | static | completed | E-003 | quality-oriented upstream behavior |
| WI-004 | Reconstruct long-page slicing behavior | lead | ManhwaSlicerService | static | completed | E-004 | constants, gutter and energy cuts |
| WI-005 | Adapt quality controls into Houri | lead | wrapper + external engine | source | completed | E-005 | uncommitted main/submodule changes |
| WI-006 | Resolve compile error and perform non-Gradle validation | lead | Inpainter.kt | source | completed | E-006 | maxOf import corrected; Gradle not rerun by agent |

## Coverage
- [x] Recon/analysis complete for in_scope assets
- [x] Critical/High candidates triaged (or N/A for pure RE)
- [x] Validated findings have Evidence (E-*)
- [x] Path documented (attack/call/solve)
- [x] Timeline continuous across major phases
- [x] Report via docs-generator
- [x] field-journal anonymized

## Refs
- skills/ops/timeline-workitem.md
- skills/ops/evidence-finding-path.md
