# Local Deployment

## Deploy insights-inventory

rhsm-subscriptions requires a connection to insights-inventory. First set up a
postgres user and database.

```
su - postgres
createuser --pwprompt -d insights
```

Run the `bin/deploy-insights` script to install the insights-inventory
project and begin running it on port 8080 by default. Check the `--help`
to see all the available options. This script will init the
git-submodule if it hasn't been already, run the database migration from
`manage.py` and then start the Flask application with the appropriate
environment variables.

Once the app has started, check to make sure you can access the API
(keep in mind that you may need to adjust the port number in the curl
command if you used a different port for deployment).

```
curl http://localhost:8080/metrics
```

## Build and Run rhsm-subscriptions

In order to build rhsm-subscriptions, make sure you have Java SDK 8 installed
(Java 1.8.x).

Create a PostgreSQL role for the application:

```
su - postgres
createuser --pwprompt -d rhsm-subscriptions
```

Run the `bin/init-application` script (with appropriate arguments if the
defaults don't suit you) to make sure the database is created and other
initialization tasks are handled.

Build and run using the following line (you can leave off the `--args` if you
are happy to run the application off port 8080)

```
./gradlew bootRun --args="--server.port=9166"
```

## Deployment Notes

RHSM Subscriptions is meant to be deployed under the context path "/". The
location of app specific resources are then controlled by the
`rhsm-subscriptions.package_uri_mappings.org.candlepin.insights` property.
This unusual configuration is due to external requirements that our
application base its context path on the value of an environment
variable. Using "/" as the context path means that we can have certain
resources (such as health checks) with a known, static name while others
can vary based on an environment variable given to the pod.

### Static Endpoints

* /actuator/health - A Spring Actuator that we use as OKD
  liveness/readiness probe.
* /actuator/info - An actuator that reads the information from
  `META-INF/build-info.properties` and reports it. The response includes
  things like the version number.

Both the health actuator and info actuator can be modified, expanded, or
extended. Please see the
[documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/production-ready-endpoints.html)
for a discussion of extension points.

### RBAC

rhsm-subscriptions uses an RBAC service to determine application authorization. The
RBAC service connection details is configured in _`rhsm-subscriptions.conf`_ as follows:

```properties
# The name of the RBAC permission application name (<APP_NAME>:*:*)
# By default this property is set to 'subscriptions'.
rhsm-subscriptions.rbacApplicationName=insights

# The path to the RBAC service's API.
rhsm-subscriptions.rbac-service.url=http://localhost:8819/api/rbac/v1

```

For development purposes, the RBAC service can be stubbed out so that the connection
to the RBAC service is bypassed and all users recieve the 'subscriptions:*:*' role. This
can be enabled by setting the following property:

```properties
rhsm-subscriptions.rbac-service.useStub=true
```

## Deploy to Openshift

Prerequisite secrets:

- `rhsm-db`: DB connection info, having `db.host`, `db.port`, `db.user`, `db.password`, and `db.name` properties.
- `host-inventory-db-readonly`: inventory read-only clone DB connection info, having `db.host`, `db.port`, `db.user`, `db.password`, and `db.name` properties.
- `ingress`: secret with `keystore.jks` and `truststore.jks` - keystores for mTLS communication with subscription-conduit.
- `tls`: having `keystore.password`, the password used for capacity ingress.

Prequisite configmaps:
- `capacity-allowlist` having `product-allowlist.txt` which is a newline-separated list of which SKUs have been approved for capacity ingress.

Adjust as desired:

```
oc process -f templates/rhsm-subscriptions-api.yml | oc create -f -
oc process -f templates/rhsm-subscriptions-capacity-ingress.yml | oc create -f -
oc process -f templates/rhsm-subscriptions-scheduler.yml | oc create -f -
oc process -f templates/rhsm-subscriptions-worker.yml | oc create -f -
```

## Release Notes

You can perform a release using `./gradlew release` **on the master
branch**. This command will invoke a
[plugin](https://github.com/researchgate/gradle-release) that will bump
the version numbers and create a release tag. When you run the command,
it will ask for input. You can specify all the arguments on the CLI if
necessary (e.g. doing a release via a CI environment). When asked for
"This release version" specify the current version **without** the
"-SNAPSHOT". When asked for the next version number enter the
appropriate value prefixed **with** "-SNAPSHOT". The plugin should offer
sane defaults that you can use.

For example. If we are working on `1.0.0-SNAPSHOT`, you would answer the
first question with `1.0.0` and the second question with
`1.0.1-SNAPSHOT`.

The plugin will create the tag and bump the version. You just need to
push with `git push --follow-tags origin master`.
