// Response handler for the "external" requests in external-api-comparison.http.
// Stores the external service's result so the next (local) request can compare against it.
client.global.set("externalStatus", String(response.status));
client.global.set("externalBody", JSON.stringify(response.status === 200 ? response.body : null));
