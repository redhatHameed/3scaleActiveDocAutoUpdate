import groovy.json.JsonSlurper
import groovy.json.JsonOutput

// token for 3scale management account
def token = "myToken"
// production route for  service (my-service.cluster-wildcard.org:443)
def productionRoute = "my-service.appsdev.ocp.lirr.org:443"
// endpoint on service to hit for swagger docs
def backendServiceSwaggerEndpoint = "http://my-service.project.svc.cluster.local:80/api/swagger"
// the system name defined for the service in 3scale
def serviceSystemName = 'my-service'

def activeDocSpecListUrl = "https://3scale-admin.cluster-wildcard.org/admin/api/active_docs.json?access_token=${token}"
def servicesEndpoint = "https://3scale-admin.cluster-wildcard.org/admin/api/services.json?access_token=${token}"

// instantiate JSON parser
def jsonSlurper = new JsonSlurper()
println('Fetching service swagger json...')
// fetch new swagger doc from service
def swaggerDoc = jsonSlurper.parseText(new URL(backendServiceSwaggerEndpoint).getText())
// get all existing docs on 3scale
println('Fetching uploaded Active Docs...')
def activeDocs =  jsonSlurper.parseText(new URL(activeDocSpecListUrl).getText()).api_docs
// find the one matching the correct service
def activeDoc = activeDocs.find { it.api_doc["system_name"] == serviceSystemName }
if ( activeDoc ) {
    println('Found Active Doc for ' + serviceSystemName)
    def services =  jsonSlurper.parseText(new URL(servicesEndpoint).getText()).services
    def service = (services.find { it.service["system_name"] == serviceSystemName }).service
    // get the auth config for this service, so we can add the correct auth params to the swagger doc
    def proxyEndpoint = "https://3scale-admin.cluster-wildcard.org/admin/api/services/${service.id}/proxy.json?access_token=${token}"
    def proxyConfig =  jsonSlurper.parseText(new URL(proxyEndpoint).getText()).proxy
    def activeDocId = activeDoc.api_doc.id
    swaggerDoc.host = productionRoute;
    // is the credential in a header or query param?
    def credentialsLocation = proxyConfig['credentials_location'] == 'headers' ? 'headers' : 'query'
    // for each method on each path, add the param for credentials
    def paths = swaggerDoc.paths.keySet() as List
    paths.each { path ->
        def methods = swaggerDoc.paths[path].keySet() as List
        methods.each {
            if (!(swaggerDoc.paths[path][it].parameters)) {
                swaggerDoc.paths[path][it].parameters = []
            }
            swaggerDoc.paths[path][it].parameters.push([
                in: credentialsLocation,
                name: proxyConfig["auth_app_key"],
                description: "User authorization key",
                required: true,
                type: "string" 
            ])
        }
    }
    // post our swagger doc, now containing the correct host and auth params to 3scale
    def activeDocSpecUpdateUrl = "https://3scale-admin.cluster-wildcard.org/admin/api/active_docs/${activeDocId}.json"
    def data = "access_token=${token}" + '&body=' + JsonOutput.toJson(swaggerDoc) + '&skip_swagger_validations=true'
    def post = new URL(activeDocSpecUpdateUrl).openConnection();
    post.setRequestMethod("PUT")
    post.setDoOutput(true)
    post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    post.setRequestProperty("Accept", "application/json")
    post.getOutputStream().write(data.getBytes("UTF-8"));
    def responseCode = post.getResponseCode();
    if (responseCode != 200) {
        println('Failed to update Active Docs for ' + serviceSystemName + '. HTTP response: ' + responseCode)
    } else {
        println('Active Docs for ' + serviceSystemName + ' updated successfully!')
    }
} else {
    println('Active Docs for ' + serviceSystemName + ' not found in 3scale.')
}
