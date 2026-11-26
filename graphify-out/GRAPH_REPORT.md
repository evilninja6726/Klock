# Graph Report - Klock  (2026-11-26)

## Corpus Check
- Corpus is ~31,144 words - fits in a single context window. You may not need a graph.

## Summary
- 794 nodes · 2024 edges · 50 communities (23 shown, 27 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 2 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- AlarmManager
- Alarm Scheduling & Audio Service
- Memory Tier & OEM Compatibility
- Room Persistence & Repositories
- Live Question Engine & Sources
- Compose Horological UI & ViewModels
- Room Persistence & Repositories
- Live Question Engine & Sources
- Compose Horological UI & ViewModels
- Live Question Engine & Sources
- Live Question Engine & Sources
- Alarm Scheduling & Audio Service
- AlarmSessionCoordinator.kt
- Room Persistence & Repositories
- Live Question Engine & Sources
- Room Persistence & Repositories
- Answer Validation & Adaptive Planner
- Live Question Engine & Sources
- Live Question Engine & Sources
- AlarmReconcileWorker.kt
- Compose Horological UI & ViewModels
- Alarm Scheduling & Audio Service
- ByokSecretStore
- Security Guards & Strix Audit
- Live Question Engine & Sources
- Security Guards & Strix Audit
- Compose Horological UI & ViewModels
- Live Question Engine & Sources
- Room Persistence & Repositories
- AlarmTimeCalculator
- Live Question Engine & Sources
- Alarm Scheduling & Audio Service
- Live Question Engine & Sources
- Memory Tier & OEM Compatibility
- Launcher Icon (mipmap-mdpi)
- Launcher Icon (mipmap-xhdpi)

## God Nodes (most connected - your core abstractions)
1. `Alarm` - 45 edges
2. `AlarmQuestion` - 41 edges
3. `ActiveAlarmSession` - 41 edges
4. `QuestionCategory` - 41 edges
5. `MainViewModel` - 32 edges
6. `KlockAppContainer` - 31 edges
7. `LiveEducationalSourcesClient` - 29 edges
8. `DifficultyPreset` - 28 edges
9. `LiveQuestionEngine` - 27 edges
10. `AnswerValidator` - 27 edges

## Surprising Connections (you probably didn't know these)
- `Horological Design System` --rationale_for--> `MechanicalLockBar()`  [EXTRACTED]
  .interface-design/system.md → app/src/main/java/com/klock/app/ui/components/KlockComponents.kt
- `Klock Clean Architecture Spec` --references--> `MemoryTierDetector`  [EXTRACTED]
  README.md → app/src/main/java/com/klock/app/core/device/MemoryAndOemAdvisor.kt
- `Strix White-Box Security Audit` --references--> `EncryptedByokSecretStore`  [EXTRACTED]
  strix_runs/phase1_audit/penetration_test_report.md → app/src/main/java/com/klock/app/data/repository/RepositoryImpls.kt
- `KlockAppContainer` --references--> `AlarmAudioController`  [EXTRACTED]
  app/src/main/java/com/klock/app/KlockApplication.kt → app/src/main/java/com/klock/app/alarm/audio/AlarmAudioController.kt
- `KlockAppContainer` --calls--> `AndroidAlarmAudioController`  [EXTRACTED]
  app/src/main/java/com/klock/app/KlockApplication.kt → app/src/main/java/com/klock/app/alarm/audio/AlarmAudioController.kt

## Import Cycles
- None detected.

## Communities (50 total, 27 thin omitted)

### Community 0 - "AlarmManager"
Cohesion: 0.05
Nodes (6): AlarmBootAndTimeReceiver, AlarmTriggerReceiver, AndroidAlarmScheduler, AlarmRingingService, NetworkCallback, ActiveAlarmActivity

### Community 2 - "Alarm Scheduling & Audio Service"
Cohesion: 0.05
Nodes (8): AlarmAudioController, AndroidAlarmAudioController, ActiveAlarmUiState, ActiveAlarmViewModel, Factory, Quad, Quint, FeedbackBanner

### Community 3 - "Memory Tier & OEM Compatibility"
Cohesion: 0.06
Nodes (13): MemoryTierDetector, OemCompatibilityAdvisor, OemGuidance, OemVendor, HUAWEI_HONOR, MOTOROLA, OPPO_REALME_ONEPLUS, SAMSUNG (+5 more)

### Community 4 - "Room Persistence & Repositories"
Cohesion: 0.10
Nodes (8): ActiveAlarmSessionEntity, AlarmDao, AlarmEntity, CategoryStatEntity, KlockDatabase, QuestionHistoryDao, QuestionHistoryEntity, SessionDao

### Community 6 - "Live Question Engine & Sources"
Cohesion: 0.18
Nodes (21): ActiveAlarmRootScreen(), ActiveCompletionScreen(), ActiveQuestionDismissalView(), EmergencyOverrideView(), QuestionCardBody(), QuickTokenStrip(), CodeWorkbenchBlock(), HairlineCard() (+13 more)

### Community 8 - "Compose Horological UI & ViewModels"
Cohesion: 0.10
Nodes (7): ThemePreference, DARK, LIGHT, SYSTEM, MainActivity, KlockPalette, KlockTheme()

### Community 9 - "Room Persistence & Repositories"
Cohesion: 0.12
Nodes (3): DataStoreSettingsRepository, AppSettings, SettingsRepository

### Community 10 - "Live Question Engine & Sources"
Cohesion: 0.10
Nodes (18): QuestionDifficulty, EASY, EASY_MEDIUM, HARD, MEDIUM, MEDIUM_HARD, QuestionFormat, CODE_ANSWER (+10 more)

### Community 11 - "Compose Horological UI & ViewModels"
Cohesion: 0.10
Nodes (4): MainViewModel, Factory, Quad, Quint

### Community 12 - "Live Question Engine & Sources"
Cohesion: 0.29
Nodes (3): QuestionSlotPlan, LiveQuestionEngine, LiveSourceDocument

### Community 13 - "Live Question Engine & Sources"
Cohesion: 0.25
Nodes (3): DirectRetrievedItem, LiveEducationalSourcesClient, SourceBaseUrls

### Community 14 - "Alarm Scheduling & Audio Service"
Cohesion: 0.20
Nodes (4): FakeAudioController, InMemoryAlarmRepository, InMemorySettingsRepository, SessionDismissalAndNetworkResilienceTest

### Community 15 - "AlarmSessionCoordinator.kt"
Cohesion: 0.13
Nodes (11): AnswerEvaluationResult, Correct, Incorrect, OverrideReason, ACCESSIBILITY, PROLONGED_OUTAGE, SAFETY_MEDICAL, RetrievalStatus (+3 more)

### Community 16 - "Room Persistence & Repositories"
Cohesion: 0.18
Nodes (4): AlarmRepositoryImpl, toDomain(), Alarm, AlarmRepository

### Community 17 - "Live Question Engine & Sources"
Cohesion: 0.13
Nodes (4): QuestionHistoryRepositoryImpl, toEntity(), QuestionHistoryRecord, QuestionHistoryRepository

### Community 18 - "Room Persistence & Repositories"
Cohesion: 0.21
Nodes (3): SessionRepositoryImpl, ActiveAlarmSession, SessionRepository

### Community 19 - "Answer Validation & Adaptive Planner"
Cohesion: 0.15
Nodes (10): DifficultyPreset, ADAPTIVE, EASY, EXPERT, HARD, NORMAL, QuestionEngine, QuestionEngine (+2 more)

### Community 21 - "Live Question Engine & Sources"
Cohesion: 0.21
Nodes (8): CategoryPerformanceStat, QuestionCategory, BUSINESS, MARKETING, POWER_BI, PYTHON, SQL, AdaptivePlanner

### Community 23 - "Compose Horological UI & ViewModels"
Cohesion: 0.21
Nodes (5): MainUiState, TopTab, ALARMS, HISTORY, SETTINGS

### Community 24 - "Alarm Scheduling & Audio Service"
Cohesion: 0.22
Nodes (4): AlarmScheduler, DeviceHardwareProfile, KlockAppContainer, KlockApplication

### Community 25 - "ByokSecretStore"
Cohesion: 0.15
Nodes (4): ByokSecretStore, EncryptedByokSecretStore, ByokSecretStore, Strix White-Box Security Audit

### Community 29 - "Compose Horological UI & ViewModels"
Cohesion: 0.31
Nodes (4): KlockMainAppScreen(), KlockTopHeader(), QuestionHistorySection(), SettingsSection()

### Community 36 - "Memory Tier & OEM Compatibility"
Cohesion: 0.50
Nodes (4): DeviceMemoryTier, HIGH_RAM, LOW_RAM, STANDARD_RAM

## Knowledge Gaps
- **51 isolated node(s):** `LOW_RAM`, `STANDARD_RAM`, `HIGH_RAM`, `XIAOMI_REDMI_POCO`, `SAMSUNG` (+46 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 230 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **27 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Alarm` connect `Room Persistence & Repositories` to `AlarmManager`, `AlarmTimeCalculator`, `Compose Horological UI & ViewModels`, `Memory Tier & OEM Compatibility`, `Alarm Scheduling & Audio Service`, `Live Question Engine & Sources`, `Room Persistence & Repositories`, `Compose Horological UI & ViewModels`, `Alarm Scheduling & Audio Service`, `AlarmSessionCoordinator.kt`, `Live Question Engine & Sources`, `Answer Validation & Adaptive Planner`, `Live Question Engine & Sources`, `Compose Horological UI & ViewModels`, `Alarm Scheduling & Audio Service`, `Room Persistence & Repositories`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **What connects `LOW_RAM`, `STANDARD_RAM`, `HIGH_RAM` to the rest of the system?**
  _51 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `AlarmManager` be split into smaller, more focused modules?**
  _Cohesion score 0.05336951605608322 - nodes in this community are weakly interconnected._
- **Why does `KlockAppContainer` connect `Alarm Scheduling & Audio Service` to `AlarmManager`, `Live Question Engine & Sources`, `Alarm Scheduling & Audio Service`, `Room Persistence & Repositories`, `Room Persistence & Repositories`, `Compose Horological UI & ViewModels`, `Live Question Engine & Sources`, `Live Question Engine & Sources`, `Room Persistence & Repositories`, `Live Question Engine & Sources`, `Room Persistence & Repositories`, `Answer Validation & Adaptive Planner`, `Live Question Engine & Sources`, `Live Question Engine & Sources`, `AlarmReconcileWorker.kt`, `Compose Horological UI & ViewModels`, `ByokSecretStore`?**
  _High betweenness centrality (0.070) - this node is a cross-community bridge._
- **Should `Compose Horological UI & ViewModels` be split into smaller, more focused modules?**
  _Cohesion score 0.06918238993710692 - nodes in this community are weakly interconnected._
- **Why does `QuestionCategory` connect `Live Question Engine & Sources` to `Compose Horological UI & ViewModels`, `Memory Tier & OEM Compatibility`, `Live Question Engine & Sources`, `Live Question Engine & Sources`, `Room Persistence & Repositories`, `Live Question Engine & Sources`, `Live Question Engine & Sources`, `Alarm Scheduling & Audio Service`, `AlarmSessionCoordinator.kt`, `Room Persistence & Repositories`, `Live Question Engine & Sources`, `Answer Validation & Adaptive Planner`, `Live Question Engine & Sources`, `Compose Horological UI & ViewModels`, `Live Question Engine & Sources`, `Compose Horological UI & ViewModels`, `Live Question Engine & Sources`?**
  _High betweenness centrality (0.066) - this node is a cross-community bridge._
- **Should `Alarm Scheduling & Audio Service` be split into smaller, more focused modules?**
  _Cohesion score 0.05333333333333334 - nodes in this community are weakly interconnected._