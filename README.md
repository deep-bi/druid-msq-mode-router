# Deep MSQ Mode Router Extension
Introduces a single native-JSON endpoint that routes each query to the native engine (hot) or MSQ (cold) based on segment timeline coverage of the query’s time range.
Keeps client tooling unchanged while enabling deep-storage querying at scale.

## Installation

1. Download the Multi-Stage Query (MSQ) extension with matching Druid version from the [releases page](https://github.com/deep-bi/druid-multi-stage-query/releases)
2. Place the `druid-multi-stage-query-<version>.jar` file into the `{DRUID_HOME}/extensions/druid-multi-stage-query/` directory
3. Place the `web-console-<version>.jar` file into the `{DRUID_HOME}/lib/` directory, replacing the existing one
4. Place the `druid-msq-mode-router-<version>.jar` file into the `{DRUID_HOME}/extensions/druid-msq-mode-router/` directory
5. Add `druid-multi-stage-query` and `druid-msq-mode-router` to the `druid.extensions.loadlist` in your `common.runtime.properties` file
For more information about how to load an extension, see [Loading extensions](https://druid.apache.org/docs/latest/configuration/extensions#loading-extensions)
6. Configure MSQ extension as per [MSQ documentation](https://druid.apache.org/docs/latest/querying/query-deep-storage)

## Configuration

#### Broker runtime properties:

| Name                                     | Default | Description                                                                                                                             |
|------------------------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `druid.deep.gateway.queryTimeout`        | `PT15M` | Global request timeout, in ISO-8601 format, also caps total MSQ polling time in cold mode and is used for all internal request timeouts |
| `druid.deep.gateway.pollIntervalSeconds` | `10`    | Interval (seconds) between MSQ task status polls when executing in a cold mode                                                          |

## Usage

To run a query using the Deep MSQ Router, POST your query to the `/druid-ext/query-router/v2` endpoint to one of your Broker nodes or to the Router

#### Routing:

* All required segments present -> hot (native engine).
* Any required segment missing -> cold (MSQ over deep storage).

By default, all queries are run in synchronous mode. This means that the client waits for the query to complete and receives the full result set in the response.
When MSQ queries executed synchronously, the query state is polled with configured interval until the query is completed, failed or reached timeout.
The decorated response is returned to the client.

Multi-stage quries can also be run in asynchronous mode. This will allow the client to submit a query and receive an immediate response containing a query ID.
To run a query in asynchronous mode, add `?mode=async` to the request URL.

_Sample request:_

```curl
curl -X POST \
  https://ROUTER:8888/druid-ext/query-router/v2 \
  -H 'Content-Type: application/json' \
  -d '{
    "queryType": "groupBy",
    "dataSource": "test",
    "granularity": "all",
    "intervals": ["2025-09-20T00:00:00.000Z/2025-10-01T00:00:00.000Z"],
    "aggregations": [
      { "type": "doubleSum", "name": "sum_value", "fieldName": "value" },
      { "type": "count", "name": "rows" }
    ],
    "context": { "timeout": 30000 }
  }'
```
_Sample Results:_
* Native -> `[{"version":"v1","timestamp":"2025-09-20T00:00:00.000Z","event":{"sum_value":275.0,"rows":11}}]`
* MSQ -> `[{"version":null,"timestamp":null,"event":{"sum_value":275.0,"rows":11}}]` // note: version and timestamp are null in MSQ results
* MSQ with async mode enabled -> `{"queryId":"query-135761b6-ce99-4130-8c06-ca850a766669","state":"ACCEPTED","createdAt":"2025-10-24T11:41:44.290Z","schema":{"sum_value":"DOUBLE","rows":"LONG"},"durationMs":-1}` // POST to `https://ROUTER:8888/druid-ext/query-router/v2?mode=async`


## Known Limitations
* Native query input only (no SQL).
* Supports scan and groupBy.
* GroupBy queries support only 'all' granularity.
* Decoration only for groupBy and ordered scan. Non-ordered scan returns raw MSQ-collected events.
* Requires the custom MSQ distribution.