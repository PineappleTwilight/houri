# Case Scope

## meta
- case_id: k3-mtl-apk
- created: 2026-09-24T10:40:16-04:00
- operator: local
- project_root: /mnt/c/users/branden/komikku-pineapple
- primary_skill: apk-reverse/SKILL.md
- primary_id: R1
- lead_role: lead
- specialist_roles: []
- hint: Reverse-engineer the public release APK from Kthree-K3/K3-Manga-AutoTranslate-Mobile, reconstruct its local MTL translation pipeline, and adapt the Komikku Houri local pipeline
- preset: none

## auth
- status: granted
- basis: written_contract
- evidence_of_auth: User explicitly requested analysis of the named public release APK in this task
- MUST NOT proceed if status != granted

## in_scope
- assets:
  - https://github.com/Kthree-K3/K3-Manga-AutoTranslate-Mobile
  - https://api.github.com/repos/Kthree-K3/K3-Manga-AutoTranslate-Mobile/*
  - https://github.com/Kthree-K3/K3-Manga-AutoTranslate-Mobile/releases/*
- surfaces: []
- activities: []

## out_of_scope
- assets: []
- activities: [dos, phishing_real_users, unrestricted_exfil]

## network_profile
- mode: authorized_target_only
- notes: |
    offline | lab_only | authorized_target_only | unrestricted_lab
    Change mode only after auth.status = granted.
    Presets: offline-sample | ctf-public | own-system

## deliverables
- report: true
- field_journal: true
- diagrams: true
- timeline: true

## constraints
- timebox: {}
- stealth: low
- data_handling: anonymize

## signoff
- ready_for_act: true
- checklist:
  - [x] auth.status = granted
  - [x] in_scope.assets non-empty OR offline sample path set
  - [x] network_profile.mode chosen
  - [x] out_of_scope reviewed
  - [x] roles assigned (see skills/ops/role-map.md)

## ops_refs
- skills/ops/scope-contract.md
- skills/ops/evidence-finding-path.md
- skills/ops/role-map.md
- skills/ops/timeline-workitem.md
- skills/ops/IDENTITY.md
