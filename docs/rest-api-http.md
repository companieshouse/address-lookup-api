# Running `rest-api.http` in IntelliJ

`rest-api.http` contains sample requests for the local API, the Postcode API and the Ordnance Survey (OS) Data Hub
download API. The OS requests need an API key, which must never be committed. The key is supplied through a
gitignored IntelliJ HTTP Client private environment file.

## Setup

1. Use IntelliJ IDEA (Ultimate has the HTTP Client built in; on Community install a compatible HTTP client plugin).
2. Get an OS Data Hub API key for the Address Lookup data package from the OS Data Hub project, or from Vault
   (`os_api_key`).
3. In the project root, copy the template:

   ```bash
   cp http-client.private.env.json.example http-client.private.env.json
   ```

4. Edit `http-client.private.env.json` and replace the placeholder with your key:

   ```json
   {
     "dev": {
       "os_api_key": "<your OS Data Hub key>"
     }
   }
   ```

   `http-client.private.env.json` is listed in `.gitignore`, so it will not be committed. Confirm with
   `git check-ignore http-client.private.env.json`.

## Running requests

1. Open `rest-api.http`.
2. Click the environment selector in the editor toolbar (or the run gutter icon) and choose **dev**.
3. Run a request with the green gutter icon next to it.

The `[OS]` requests send the header `key: {{os_api_key}}`, which IntelliJ resolves from your private file. If the
variable is unresolved, the environment is not selected or the key name does not match.

The local requests (`http://localhost:8080/...`) need the service running locally; the Postcode API request needs
network access to the CIDEV environment.

## Notes

- Never put the real key in `rest-api.http`, in `http-client.env.json` (the non-private file is committable), or in
  any tracked file.
- If a key is ever committed, treat it as compromised and rotate it in OS Data Hub.
- Other editors: VS Code REST Client uses `.vscode/settings.json` (`rest-client.environmentVariables`) or `{{$dotenv}}`
  with a gitignored `.env`; the `{{os_api_key}}` syntax is the same.
