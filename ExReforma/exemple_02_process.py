import pandas as pd
from reforma import ReForma

# 1. Create a dummy event log (e.g., E-commerce Navigation)
data = {
    'session_id': [1, 1, 1, 2, 2, 3, 3, 3, 3],
    'event':      ['view', 'cart', 'buy', 'view', 'exit', 'view', 'view', 'cart', 'exit'],
    'timestamp':  [ 100,   105,    110,   115,    120,    125,    130,    135,    140]
}
df = pd.DataFrame(data)

# 2. Initialize ReForma and Discover the Model directly from the DataFrame
model = ReForma()
sessions_trace = model.create_from_dataframe(
    df=df,
    session_col='session_id',
    event_col='event',
    time_col='timestamp',
    model_name="ECommerce_Mined",
    save_model_path="ecommerce_mined.r"
)

print("=== Model extracted from data ===")
print(model.get_stats())

# 3. The discovered model has uniform/initial probabilities. 
# Let's TRAIN the model with the exact extracted traces to adjust the statistical weights!
print("\nTraining model with extracted sessions...")
model.train(sessions_trace)

# 4. Clean noise (Pruning): Remove paths with less than 10% (0.10) probability
print("\nApplying Delta Cut (0.10) to remove rare paths...")
model.delta_cut(0.10)

print("\n=== State after training and cutting ===")
print(model.state.summary())

# Save the final trained and cleaned model
model.save_source("ecommerce_final.r")
print("\nModel saved to 'ecommerce_final.r'")