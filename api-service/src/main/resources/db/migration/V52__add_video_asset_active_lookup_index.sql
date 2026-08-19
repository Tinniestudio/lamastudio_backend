-- Backs PlaybackServiceImpl's active-asset lookup (WHERE content_id/episode_id = ? AND
-- asset_type = ? AND is_active = true) introduced in this same change. Two indexes since
-- content_id and episode_id are two different lookup paths (movie vs. episode playback),
-- never queried together.
CREATE INDEX idx_video_assets_active_by_content ON video_assets (content_id, asset_type, is_active) WHERE is_active = true;
CREATE INDEX idx_video_assets_active_by_episode ON video_assets (episode_id, asset_type, is_active) WHERE is_active = true;
