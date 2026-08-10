from reforma import ReForma

system_code = """
name SmartHome
int alarm_active = 0

init Safe

Safe ---> Intrusion: break_glass (0.1)
Safe ---> Safe: normal_day (0.9)

Intrusion ---> Police: call_police (0.7) if (alarm_active == 1)
Intrusion ---> Robbery: escape (0.3)

// Alarm activation rule
break_glass ->> call_police: trigger_alarm (1.0) then {
    alarm_active' := 1
}
"""

model = ReForma()
model.load(system_code)

print("--- Security System Analysis ---")

# 1. Find the best path to a state (e.g.: What is the most probable path to Intrusion?)
path = model.find_best_path(target_type="state", target_value="Intrusion", criterion="max")
print("\nMost probable path to Intrusion:")
print(path)

# 2. Quantitative Verification (PCTL)
# "What is the probability of the system evolving to call the police from the Intrusion state?"
prob_police = model.check_pdl_value("Intrusion", "{P=?[F Police]}")
print(f"\nProbability of the Police being called after intrusion: {prob_police:.2f}")

# 3. Qualitative Verification (Returns Boolean)
# "Is it guaranteed (Probability > 0.99) that the active alarm prevents the final Robbery state?"
is_safe = model.check_pdl_value("Safe", "{P>=0.99[G !Robbery]}")
print(f"Is the system >99% safe against robberies? {is_safe}")

# 4. Find Deadlocks using the built-in function
problems = model.check_problems()
print("\nModel Sanity Report:")
print(problems)