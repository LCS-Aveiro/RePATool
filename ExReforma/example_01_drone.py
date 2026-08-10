from reforma import ReForma

# 1. Define the model (Delivery Drone with Adaptation)
drone_code = """
name DroneDelivery

init Base

// Static Behavior
Base ---> Flying: launch (1.0)
Flying ---> Delivered: success (0.8)
Flying ---> Crashed: fail (0.2)
Delivered ---> Base: return (1.0)
Crashed ---> Base: rescue (1.0) disabled

// Dynamic Rule: If there is a failure, activate the rescue mission
fail ->> rescue: activate_rescue (1.0)
"""

# 2. Initialize the library and load the model
model = ReForma()
model.load(drone_code, name="DroneDelivery")

print("=== Initial State ===")
print(model.state.summary())

# 3. Take steps in the simulation
print("\n[Action] The drone was launched...")
model.step("launch")

print("\n[Action] An engine failure occurred!")
model.step("fail")

print("\n=== State after failure (Note that 'rescue' was activated by the rule) ===")
print(model.state.summary())

# 4. Generate a static image of the graph (Requires matplotlib and networkx)
model.save_image_plt("drone_graph.png")
print("\nGraph saved as 'drone_graph.png'!")

# In a Jupyter Notebook, you can use:
# model.show_interactive()