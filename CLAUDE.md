# Backend

## Subscription issue
- exsisting user with active plan keep getting action to to setup card
- update the system to check if exsisting user have active plan and check if active plan havent expired to setup their profile to match the new requirement (migration process)


# client
## card setup update
- the frontend (client-web) should be update to only prompt them to setup card, but should not block them from accessing the platform

## platform authorization
- update authorization, only new users are require to setuo card before them can access anything on the platform
- exsisting old user user with active plan can access the platform and backend should migrate the account to new process, and prompt them to setup card but the process should be non-block as the can close the page
- but when exsisting user or new user plan expired, they can access the platform but they should not be able to watch any content, they should be redirect to billing to confirm/turn-on/set new card for subscription cahrging
- 