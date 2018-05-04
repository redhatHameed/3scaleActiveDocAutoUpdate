import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.URLEncoder

//adminBaseUrl: base URL for 3scale admin
//token: token for 3scale management account
//productionRoute: production route for  service (my-service.cluster-wildcard.org:443)
//backendServiceSwaggerEndpoint: endpoint on service to hit for swagger docs
//serviceSystemName: the system name defined for the service in 3scale
def update3scaleActiveDoc(
    String adminBaseUrl,
    String token, 
    String productionRoute, 
    String backendServiceSwaggerEndpoint,
    String serviceSystemName) {

    def activeDocSpecListUrl = "${adminBaseUrl}/admin/api/active_docs.json?access_token=${token}"
    def servicesEndpoint = "${adminBaseUrl}/admin/api/services.json?access_token=${token}" 

    // instantiate JSON parser
    def jsonSlurper = new JsonSlurper()
    println('Fetching service swagger json...')
    // fetch new swagger doc from service
    def swaggerDoc = jsonSlurper.parseText(new URL(backendServiceSwaggerEndpoint).getText())   
    def services =  jsonSlurper.parseText(new URL(servicesEndpoint).getText()).services
    def service = (services.find { it.service['system_name'] == serviceSystemName }).service
    // get the auth config for this service, so we can add the correct auth params to the swagger doc
    def proxyEndpoint = "${adminBaseUrl}/admin/api/services/${service.id}/proxy.json?access_token=${token}"
    def proxyConfig =  jsonSlurper.parseText(new URL(proxyEndpoint).getText()).proxy
    swaggerDoc.host = productionRoute;
    // is the credential in a header or query param?
    def credentialsLocation = proxyConfig['credentials_location'] == 'headers' ? 'header' : 'query'
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
                name: proxyConfig['auth_user_key'],
                description: 'User authorization key',
                required: true,
                type: 'string' 
            ])
        }
    }  
    // get all existing docs on 3scale
    println('Fetching uploaded Active Docs...')
    def activeDocs =  jsonSlurper.parseText(new URL(activeDocSpecListUrl).getText())['api_docs']
    // find the one matching the correct service (or not)
    def activeDoc = activeDocs.find { it['api_doc']['system_name'] == serviceSystemName }   
    if ( activeDoc ) {
        println('Found Active Doc for ' + serviceSystemName)
        def activeDocId = activeDoc['api_doc'].id
        // update our swagger doc, now containing the correct host and auth params to 3scale
        def activeDocSpecUpdateUrl = "${adminBaseUrl}/admin/api/active_docs/${activeDocId}.json"
        def name = swaggerDoc.info.title != null ? swaggerDoc.info.title : serviceSystemName
        def data = "access_token=${token}&body=${URLEncoder.encode(JsonOutput.toJson(swaggerDoc), 'UTF-8')}&skip_swagger_validations=false"
        makeRequestwithBody(activeDocSpecUpdateUrl, data, 'PUT')
    } else {
        println('Active Docs for ' + serviceSystemName + ' not found in 3scale. Creating a new Active Doc.')
        // post our new swagger doc, now containing the correct host and auth params to 3scale
        def activeDocSpecCreateUrl = "${adminBaseUrl}/admin/api/active_docs.json"
        def name = swaggerDoc.info.title != null ? swaggerDoc.info.title : serviceSystemName
        def data = "access_token=${token}&name=${name}&system_name=${serviceSystemName}&body=${URLEncoder.encode(JsonOutput.toJson(swaggerDoc), 'UTF-8')}&skip_swagger_validations=false"      
        makeRequestwithBody(activeDocSpecCreateUrl, data, 'POST')
    }
}

def makeRequestwithBody(url, body, method) {
    def post = new URL(url).openConnection();
    post.setRequestMethod(method)
    post.setDoOutput(true)
    post.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
    post.setRequestProperty('Accept', 'application/json')
    post.getOutputStream().write(body.getBytes('UTF-8'))
    def responseCode = post.getResponseCode();
    if (responseCode != 200 && responseCode != 201) {
        println('Failed to update/create Active Docs. HTTP response: ' + responseCode)
        assert false
    } else {
        println('Active Docs updated/created successfully!')
    }
}
return this
