# MedicalSystem OCL/OCLite Demo Expected Results

Dataset: `MedicalSystem_states.soil`

| # | OCL | Expected |
|---|-----|----------|
| 1 | `Doctor.allInstances()->size()` | `3` |
| 2 | `Patient.allInstances()->size()` | `3` |
| 3 | `Nurse.allInstances()->size()` | `3` |
| 4 | `Hospital.allInstances()->size()` | `2` |
| 5 | `Person.allInstances()->size()` | `9` |
| 6 | `Doctor.allInstances()->forAll(d \| d.age >= 0)` | `true` |
| 7 | `Patient.allInstances()->forAll(p \| p.age >= 0)` | `true` |
| 8 | `Hospital.allInstances()->forAll(h \| h.totalBudget > 0)` | `true` |
| 9 | `Doctor.allInstances()->forAll(d \| d.shiftSchedule->forAll(s \| s->size() <= 3))` | `true` |
| 10 | `Patient.allInstances()->exists(p \| p.isEmergency = true)` | `true` |
| 11 | `Patient.allInstances()->one(p \| p.age > 80)` | `true` |
| 12 | `Patient.allInstances()->select(p \| p.age >= 18)->size()` | `2` |
| 13 | `Patient.allInstances()->reject(p \| p.age < 18)->size()` | `2` |
| 14 | `Doctor.allInstances()->select(d \| d.isAvailable = true)->size()` | `2` |
| 15 | `Doctor.allInstances()->collect(d \| d.consultationFee)->sum()` | `730.0` |
| 16 | `Doctor.allInstances()->collect(d \| d.consultationFee)->max()` | `300.0` |
| 17 | `Doctor.allInstances()->collect(d \| d.consultationFee)->min()` | `180.0` |
| 18 | `Nurse.allInstances()->collect(n \| n.hourlyRate)->sum()` | `122.5` |
| 19 | `Hospital.allInstances()->collect(h \| h.totalBudget)->sum()` | `2250000.0` |
| 20 | `Hospital.allInstances()->collect(h \| h.availableICUs)->sum()` | `35.0` |
| 21 | `Hospital.allInstances()->collect(h \| h.rating)->max()` | `4.8` |
| 22 | `doctorHouse.oclIsKindOf(Person)` | `true` |
| 23 | `patientAlice.oclIsTypeOf(Patient)` | `true` |
| 24 | `nurseAnna.oclIsKindOf(Person)` | `true` |
| 25 | `centralHospital.departments->size()` | `2` |
| 26 | `cityHospital.departments->size()` | `1` |
| 27 | `centralHospital.departments->includes(cardiologyDept)` | `true` |
| 28 | `centralHospital.departments->includes(neurologyDept)` | `false` |
| 29 | `doctorHouse.shiftSchedule->collect(s \| s->size())->sum()` | `6.0` |
| 30 | `doctorGrey.patientQueue->size()` | `1` |
| 31 | `doctorStrange.patientQueue->isEmpty()` | `true` |
| 32 | `patientAlice.symptoms->size()` | `2` |
| 33 | `patientAlice.symptoms->includes('fever')` | `true` |
| 34 | `patientBob.allergies->includes('latex')` | `true` |
| 35 | `patientCarol.allergies->isEmpty()` | `true` |
| 36 | `Medication.allInstances()->collect(m \| m.price)->sum()` | `36.4` |
| 37 | `Medication.allInstances()->collect(m \| m.price)->max()` | `15.0` |
| 38 | `Medication.allInstances()->select(m \| m.price > 10)->size()` | `2` |
| 39 | `Patient.allInstances()->collect(p \| p.weight)->max()` | `70.0` |
| 40 | `Patient.allInstances()->collect(p \| p.height)->min()` | `1.58` |

Notes:

- The OCLite grammar currently requires untyped iterators, for example `p | p.age > 18`, not `p : Patient | ...`.
- These queries avoid enum literals, `Set{...}`, `if then else`, and operation body calls because OCLite does not currently parse/evaluate those forms.
- Numeric results may be printed as integer-like values in USE and `double` values in OCLite. Compare by numeric value, not exact text formatting.
