## Batch 13 — Partner Portal

1. admin promote user oto partner, and they can use same credetaail to login to there portal by checking if parner role is included
2. yeah, partner can update there profile, 
3. partner upload logon follow same presign upload
4. verification automatic when admin proote the user, and admin can still update using the endpoint, for partner that voilate regulation
5. remove gross revenue for now, only admin own that
6. depend on question 5
7. use partners/contents for scalability
8. merge both into a big and flexible endpoint
9. remove payment event, audit_log
10. seperate field



## Batch 14 — Admin Moderation
1. content moderation is completed, and add dashboard, user and partners
2. add BAN to it, suspended account can be recover with appeal, delete make use of soft-delete, and BAN, block user from total access
3. i1/status is basically use to uupdate users status, no other operation or update will be allow, while user/id can be user to perform account update
4. admin promote user to partner, but there will be an endpoint for user to user to apply for becoming a partner and when admin approve the will be prompt to complete partner profile
5. when user is suspended all token are revoke
6. audit_log can be query by admin on dashboard
7. use rabbitMQ and update proxy that have been created to use it
8. minoO will be replce with s3 bucket in production, so avoid hard coding, and keep track od the bytes in db on upload completion
9. user delete should make use of softdelete
10. yes, good, seperated admin endpoint for getting content 

## Batch 15 — Notification System
1. add full migration
2. replace with semantic event types
3. notification_template eed crud, for admin management
4. preference per channel
5. create seperate endpoint for unread message count
6. it should be in notification preference
7. yes, it live on api-service
8. build and abstract for now
9. notification greatr then 90days should be clean up
10. worker, update videoassest and also send notification


## Batch 16 — Analytics
1. contents.views_count get increment asyncly
2. payback/progress should publish PROGRESS_UPDATE to queue directly
3. check batch 13 8
4. it make use of existing payment table
6. granularity will make use of one point per calender (mon-sun)
7. yes, it can be track as user null, for better analysis
8. yes, partner can be able to
9. Should analytics API responses always read from content_analytics_daily (up to 1 hour stale), 
10. should suppport CSV export

## Batch 17 — Background Jobs
1. the distributd lock machanism is ok
2. When an expired UploadSession is cleaned up, only the db row should be deleted, so user can continue
3. add the column to notification table for easy tracking
4. stalejob recovery a VideoAsset in PROCESSING status where updatedAt < now() - 60 min
5. failed assets just be deleted after 7 days
6. write to audit_log and notify admin
7. if expired_at is not included add it and setup job for cleaning expired token
8. create job_execution_log for all job execution process

## Batch 18 — Observability
1. the cloud collect the JSOnlog
2. health actuator should be public why metrics and prometheus can only be access by admin
3. tinniestudio.com that the domian name and application should allow reqest from *.tinnieStudio.com
4. ensure ratelimit are inlcuded where need to avoid user overwheelming the system and prevent bot spamming 
5. audit index that are missing and added it for query optimization
6. use the best and simple load testing tools
7. yes, include prometheus + Grafana stck for docker

## Cross-cutting

CX-1. Batch combining
Should any remaining batches be combined to reduce context-switching? Strong candidates: 13+14 (both are portal/dashboard views with similar patterns), or 15+17 (notification consumer + cleanup jobs are tightly coupled).