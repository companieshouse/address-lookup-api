// Response handler for the "local" requests in external-api-comparison.http.
// Compares the local response with the external one saved by save-external.js.
var externalStatus = Number(client.global.get("externalStatus"));
var externalBody = JSON.parse(client.global.get("externalBody"));

client.test("status matches external (" + externalStatus + ")", function () {
    client.assert(response.status === externalStatus,
        "local status " + response.status + " != external status " + externalStatus);
});

if (externalStatus === 200) {
    client.test("body has the same shape as external", function () {
        // IntelliJ decorates the response body object with helper members (onEachLine, onEachMessage);
        // a JSON round trip leaves only the real fields.
        var local = JSON.parse(JSON.stringify(response.body));
        if (Array.isArray(externalBody)) {
            client.assert(Array.isArray(local), "local body is not an array");
            client.assert((local.length === 0) === (externalBody.length === 0),
                "emptiness differs: local " + local.length + " vs external " + externalBody.length);
        } else {
            client.assert(local !== null && typeof local === "object" && !Array.isArray(local),
                "local body is not an object");
            client.assert(Object.keys(local).sort().join() === Object.keys(externalBody).sort().join(),
                "fields differ: local " + Object.keys(local).join() + " vs external " + Object.keys(externalBody).join());
        }
    });
}
