-- V61__notification_templates_channel_unique.sql

-- Was UNIQUE on event_type alone, so an event type could only ever have one template row
-- with one channel. Moving to (event_type, channel) lets an event type have up to two active
-- rows — one IN_APP, one EMAIL — each with independent copy, needed so a single event can
-- deliver via both channels with different wording (email can be longer/more formal).
ALTER TABLE notification_templates DROP CONSTRAINT IF EXISTS notification_templates_event_type_key;
ALTER TABLE notification_templates ADD CONSTRAINT notification_templates_event_type_channel_key
    UNIQUE (event_type, channel);
