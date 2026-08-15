UPDATE subscription_plans
SET
    price = 7.99,
    updated_at = NOW()
WHERE name = 'SILVER';

UPDATE subscription_plans
SET
    price = 9.99,
    updated_at = NOW()
WHERE name = 'GOLD';