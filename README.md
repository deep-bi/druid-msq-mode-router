# Deep MSQ Mode Router Extension
Introduces a single endpoint that routes each query to the correct Druid backend based on query type and segment timeline coverage.
SQL queries go to the native SQL engine, segmentMetadata queries go to the broker's native-query endpoint, and scan/groupBy queries are routed hot (native engine) or cold (MSQ over deep storage) depending on whether all required segments are present in the broker's timeline.
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

To run a query using the Deep MSQ Router, POST your query to the `/druid-ext/query-router/v2` endpoint on one of your Broker nodes or on the Router.

#### Supported query types and routing

_Segment timeline: the broker's in-memory index of which segments are loaded on historicals and immediately queryable. If a query's interval falls within it, the query goes HOT; if it extends beyond (or the datasource has no loaded segments), it goes COLD via MSQ over deep storage._

| Query type | Detection | HOT endpoint | COLD endpoint | HOT/COLD decision |
|---|---|---|---|---|
| SQL | No `queryType` field in body | `/druid/v2/sql` | `/druid/v2/sql/statements` (MSQ async) | Interval extracted from `WHERE __time` clause (Segment timeline); no interval -> always COLD |
| `segmentMetadata` | `queryType == "segmentMetadata"` | `/druid/v2/` (native) | - | Always HOT |
| `scan` | `queryType == "scan"` | `/druid/v2/` | `/druid/v2/native/statements/` | Segment timeline |
| `groupBy` | `queryType == "groupBy"` | `/druid/v2/` | `/druid/v2/native/statements/` | Segment timeline |
| Other native types | Any other `queryType` | `/druid/v2/` | Falls back to HOT | Always forwarded to Druid direct |

#### HOT vs COLD decision

For **native scan/groupBy**: all required segments present in the broker timeline -> HOT; any segment missing -> would be COLD

For **SQL**: the router parses the `WHERE` clause using Calcite's SQL parser (same dialect as Druid's native planner) to extract a time interval.
Supported patterns:
- `TIME_IN_INTERVAL(__time, 'start/end')`
- `__time >= 'X' AND __time < 'Y'` (also `>`, `<=`)
- `__time BETWEEN 'X' AND 'Y'`

If an interval is found and the datasource can be identified, the interval is checked against the broker timeline: inside -> HOT, outside -> COLD. If no interval is found, or the datasource cannot be extracted, the query defaults to COLD to ensure complete results across all history.


#### SQL sample request

```curl
curl -X POST \
  https://ROUTER:8888/druid-ext/query-router/v2 \
  -H 'Content-Type: application/json' \
  -d '{"query": "SELECT COUNT(*) FROM test WHERE __time >= TIMESTAMP '\''2025-09-20'\'' AND __time < TIMESTAMP '\''2025-10-01'\''"}'
```

#### Native groupBy sample request

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

_Sample results (native groupBy):_
* Hot path: `[{"version":"v1","timestamp":"2025-09-20T00:00:00.000Z","event":{"sum_value":275.0,"rows":11}}]`
* Cold path (MSQ): `[{"version":null,"timestamp":null,"event":{"sum_value":275.0,"rows":11}}]`
* Cold path with async mode: `{"queryId":"query-135761b6-ce99-4130-8c06-ca850a766669","state":"ACCEPTED","createdAt":"2025-10-24T11:41:44.290Z","schema":{"sum_value":"DOUBLE","rows":"LONG"},"durationMs":-1}`

## Known Limitations
* SQL interval extraction does not support subqueries or joins (multiple datasources); these fall back to COLD routing to ensure complete results.
* SQL hot queries always run synchronously (poll-to-completion). The `?mode=async` parameter has no effect.
* Native query types except `scan` and `groupBy`, are always routed HOT regardless of segment timeline coverage.
* Result decoration (matching native response shape) applies only to groupBy and ordered scan on the Native cold path. Non-ordered scan returns raw MSQ-collected events.
* Requires the custom MSQ distribution (druid-multi-stage-query).
* Tested against Druid 31.0.2.
