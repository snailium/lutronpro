/**
 *  Lutron Pro Service Manager
 *
 *		5/10/2017		Version:1.0				Initial Public Release 
 *		
 *  Copyright 2017 Nate Schwartz
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import org.json.JSONObject

definition(
		name: "Lutron Pro Service Manager",
		namespace: "njschwartz",
		author: "Nate Schwartz",
		description: "Interface to talk with Lutron SmartBridge and add Pico Remotes",
		category: "SmartThings Labs",
		iconUrl: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/Lutron.png",
		iconX2Url: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/Lutron.png",
		iconX3Url: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/Lutron.png",)


preferences {
    page(name:"mainPage", title:"Configuration", content:"mainPage")
    page(name:"piDiscovery", title:"Raspberry Pi Discover", content:"piDiscovery")
    page(name:"switchDiscovery", title:"Lutron Device Setup", content:"switchDiscovery")
    page(name:"sceneDiscovery", title:"Lutron Scene Setup", content:"sceneDiscovery")
}

def mainPage() {
	//Check to see if the Raspberry Pi already exists if not load pi discovery and if so load device discovery
	def rpi = getDevices()
	if (rpi) {
		return switchDiscovery()
	} else {
		return piDiscovery()
	}
}

//Preferences page to add raspberry pi devices
def piDiscovery() {
    def refreshInterval = 5
    if(!state.subscribe) {
        log.debug('Subscribing to updates')
        // subscribe to M-SEARCH answers from hub
        subscribe(location, null, ssdpHandler, [filterEvents:false])
        state.subscribe = true
    }
    // Perform M-SEARCH
    log.debug('Performing discovery')
    //sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-upnp-org:device:RPi_Lutron_Caseta:", physicalgraph.device.Protocol.LAN))
	ssdpDiscover()
    
    //Populate the preferences page with found devices
    def devicesForDialog = getDevicesForDialog()
    if (devicesForDialog != [:]) {
    	refreshInterval = 10
    }
    
    return dynamicPage(name:"piDiscovery", title:"Server Discovery", nextPage:"switchDiscovery", refreshInterval: refreshInterval, uninstall: true) {
        section("Select your Raspberry Pi/Server") {
            input "selectedRPi", "enum", required:false, title:"Select Raspberry Pi \n(${devicesForDialog.size() ?: 0} found)", multiple:false, options:devicesForDialog, image: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/RaspPi.png"
        } 
    }
}

void ssdpDiscover() {
	 sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-upnp-org:device:RPi_Lutron_Caseta:1", physicalgraph.device.Protocol.LAN))
}

//Preferences page to add Lutron Caseta Devices
def switchDiscovery() {
    def refreshInterval = 10
    //log.debug selectedSwitches
    
    //Populate the preferences page with found devices
    def switchOptions = switchesDiscovered()
    def picoOptions = picosDiscovered()
    discoverLutronDevices()
    if (switchOptions != [:]) {
    	refreshInterval = 10
    }
    
    return dynamicPage(name:"switchDiscovery", title:"Switch Discovery", nextPage:"sceneDiscovery", refreshInterval: refreshInterval, uninstall: true) {
        
        section("Switches") {
            input "selectedSwitches", "enum", required:false, title:"Select Switches \n(${switchOptions.size() ?: 0} found)", multiple:true, options:switchOptions, image: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/LutronCasetaSwitch.png"
        }
		
        section("Pico's") {
            input "selectedPicos", "enum", required:false, title:"Select Pico's \n(${picoOptions.size() ?: 0} found)", multiple:true, options:picoOptions, image: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/LutronCasetaPico.png"
        }
        
        section {
        	paragraph "Please note that if you do not have a Pro Hub you cannot use your Pico's to control devices in ST and thus you should ignore this section. Selecting Picos with a standard hub will simply add a useless device into ST."
   		 }
    }
}

def sceneDiscovery() {
    def refreshInterval = 5
	//Populate the preferences page with found devices
    def sceneOptions = scenesDiscovered()
    discoverScenes()
    if (sceneOptions != [:]) {
    	refreshInterval = 100
    }
    return dynamicPage(name:"sceneDiscovery", title:"Scene Discovery", nextPage:"", refreshInterval: refreshInterval, install: true, uninstall: true) {
        section("Select your scenes") {
            input "selectedScenes", "enum", required:false, title:"Select Scenes \n(${sceneOptions.size() ?: 0} found)", multiple:true, options:sceneOptions,image: "https://cdn.rawgit.com/njschwartz/Lutron-Smart-Pi/master/resources/images/LutronCasetaScenes.png"
        }
    }
}

/* Callback when an M-SEARCH answer is received */
def ssdpHandler(evt) {
    if(evt.name == "ping") {
        return ""
    }
    
    def description = evt.description
    def hub = evt?.hubId
    def parsedEvent = parseDiscoveryMessage(description)
    parsedEvent << ["hub":hub]
    if (parsedEvent?.ssdpTerm?.contains("schemas-upnp-org:device:RPi_Lutron_Caseta:")) {
        def devices = getDevices()
        if (!(devices."${parsedEvent.mac}")) { //if it doesn't already exist
            devices << ["${parsedEvent.mac}":parsedEvent]
        } else { // just update the values
            def d = devices."${parsedEvent.mac}"
            boolean deviceChangedValues = false
            if(d.ip != parsedEvent.ip || d.port != parsedEvent.port) {
                d.ip = parsedEvent.ip
                d.port = parsedEvent.port
                deviceChangedValues = true
                def child = getChildDevice(parsedEvent.mac)
				if (child) {
					child.sync(parsedEvent.ip, parsedEvent.port)
                }
            }
        }
    }
}

//Creates a map to populate the switches pref page
Map switchesDiscovered() {
	def switches = getSwitches()
	def devicemap = [:]
	if (switches instanceof java.util.Map) {
		switches.each {
			def value = "${it.value.name}"
			def key = it.value.dni
			devicemap["${key}"] = value
		}
	}
	return devicemap
}

//Creates a map to populate the picos pref page
Map picosDiscovered() {
	def picos = getPicos()
	def devicemap = [:]
	if (picos instanceof java.util.Map) {
		picos.each {
			def value = "${it.value.name}"
			def key = it.value.dni
			devicemap["${key}"] = value
		}
	}
	return devicemap
}

//Creates a map to populate the scenes pref page
Map scenesDiscovered() {
	def scenes = getScenes()
	def devicemap = [:]
	if (scenes instanceof java.util.Map) {
		scenes.each {
			def value = "${it.value.name}"
			def key = it.value.id
			devicemap["${key}"] = value
		}
	}
	return devicemap
}


//Returns all found switches added to app.state
def getSwitches() {
	if (!state.switches) { 
    	state.switches = [:] 
    }
    state.switches
}

def getScenes() {
    if (!state.scenes) { 
    	state.scenes = [:] 
    }
    state.scenes
}

def getPicos() {
	if (!state.picos) { 
    	state.picos = [:] 
    }
    state.picos
}

//Request device list from raspberry pi device
private discoverLutronDevices() {
	log.debug "Discovering your Lutron Devices"
    def devices = getDevices()
    def ip
    def port
    devices.each {
    	ip = it.value.ip
        port = it.value.port
    }
   
   //Get swtiches and picos and add to state
	sendHubCommand(new physicalgraph.device.HubAction([
		method: "GET",
		path: "/devices",
		headers: [
			HOST: ip + ":" + port
		]], "${selectedRPi}", [callback: lutronHandler]))
}

def discoverScenes() {
   
   log.debug "Discovering your Scenes"
   def devices = getDevices()
   def ip
   def port
   devices.each {
       ip = it.value.ip
       port = it.value.port
    }
   
   //Get scenes and add to state
   sendHubCommand(new physicalgraph.device.HubAction([
		method: "GET",
		path: "/scenes",
		headers: [
			HOST: ip + ":" + port
		]], "${selectedRPi}", [callback: sceneHandler]))   
}


def sceneHandler(physicalgraph.device.HubResponse hubResponse) {
	log.debug 'in the scene handerl'
    def body = hubResponse.json
    if (body != null) {
    	log.debug body
        def scenes = getScenes()
        body.each { k ->
        	def virtButtonNum 
            if(k.IsProgrammed == true) {
            	virtButtonNum = k.href.substring(15)
            	scenes[k.href] = [id: k.href, name: k.Name, virtualButton: virtButtonNum, dni: k.href, hub: hubResponse.hubId]
            }
        }
        log.debug scenes
    }
    
}

//Handle device list request response from raspberry pi
def lutronHandler(physicalgraph.device.HubResponse hubResponse) {
    def slurper = new groovy.json.JsonSlurper()
    def body = hubResponse.json
    log.debug body
    
    if (body != null) {
        def switches = getSwitches()
        switches.clear()
        def deviceList = body['Devices']
        body.each { k ->
            log.debug(k.Name)
           
            if(k.DeviceType == "WallDimmer" || k.DeviceType == "PlugInDimmer" || k.DeviceType == "WallSwitch") {
                switches[k.SerialNumber] = [name: k.Name, deviceID: k.ID, zone: k.LocalZones[0].href.substring(6), dni: k.SerialNumber, hub: hubResponse.hubId, hubIP: k.HubIP, deviceType: k.DeviceType]
                //log.debug switches
            } else if (k.DeviceType == "Pico3ButtonRaiseLower" || k.DeviceType == "Pico2Button") {
            	picos[k.SerialNumber] = [name: k.Name , deviceID: k.ID , dni: k.SerialNumber, hub: hubResponse.hubId, hubIP: k.HubIP, deviceType: k.DeviceType]
                //log.debug picos
            }
      
        }
    }
    
}

/* Generate the list of devices for the preferences dialog */
def getDevicesForDialog() {
    def devices = getDevices()
    def map = [:]
    devices.each {
        def value = convertHexToIP(it.value.ip) + ':' + convertHexToInt(it.value.port)
        def key = it.value.mac
        map["${key}"] = value
    }
    map
}

/* Get map containing discovered devices. Maps USN to parsed event. */
def getDevices() {
    if (!state.devices) { state.devices = [:] }
    state.devices
}

def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"
	unsubscribe()
	initialize()
}

def initialize() {
	unschedule()
    unsubscribe()
    
    log.debug ('Initializing')
    log.debug "did I selected a switch " + selectedSwitches
    
    def selectedDevices = selectedRPi
    if (selectedSwitches != null) {
    	selectedDevices += selectedSwitches 
    }
    if (selectedPicos != null) {
    	selectedDevices += selectedPicos 
    }
    if (selectedScenes != null) {
    	selectedDevices += selectedScenes 
    }
    
    log.debug "The selected devices are: " + selectedDevices + ". Any other devices will be ignored or deleted"

    def deleteDevices = (selectedDevices) ? (getChildDevices().findAll { !selectedDevices.contains(it.deviceNetworkId) }) : getAllChildDevices()
    log.debug "Devices that will be deleted are: " + deleteDevices
    deleteDevices.each { deleteChildDevice(it.deviceNetworkId) } 

    //If a raspberry pi was actually selected add the child device pi and child device switches
    if (selectedRPi) {
    	addBridge()
        addSwitches()
        addPicos()
        addScenes() 
    }
    
    runEvery5Minutes("ssdpDiscover")
}

def addBridge() {
    //for each of the raspberry pi's selected add as a child device
    def dni = selectedRPi
    // Check if child already exists
    def d = getAllChildDevices()?.find {
        it.device.deviceNetworkId == dni
    }

    //Add the Raspberry Pi
    if (!d) {
        def ip = devices[selectedRPi].ip
        def port = devices[selectedRPi].port
        log.debug("Adding your server device with the DNI ${dni} and address ${ip}:${port}")
        d = addChildDevice("njschwartz", "Raspberry Pi Lutron Caseta", dni, devices[selectedRPi].hub, [
            "label": "PI/Caseta at: " + convertHexToIP(ip) + ':' + convertHexToInt(port),
            "data": [
                "ip": ip,
                "port": port,
                "ssdpUSN": devices[selectedRPi].ssdpUSN,
                "ssdpPath": devices[selectedRPi].ssdpPath
            ]
        ])
        d.sendEvent(name: "networkAddress", value: "${ip}:${port}")
    }
}

def addSwitches() {

	selectedSwitches.each { id ->
    	def allSwitches = getSwitches()
        log.debug "Selected switch is " + selectedSwitches
        def name = allSwitches[id].name
        def hubIP = allSwitches[id].hubIP
        def zone = allSwitches[id].zone
        def deviceID = allSwitches[id].deviceID
        def deviceType = allSwitches[id].deviceType
        log.debug "Device is: " + device
  
        def dni = id
        //add the dni to the switch state variable for future lookup
        allSwitches[id].dni = dni

        // Check if child already exists
        def d = getAllChildDevices()?.find {
            it.device.deviceNetworkId == dni
        }
		def hubId = switches[id].hub

        if (!d) {
        	if (deviceType == "WallDimmer" || deviceType == "PlugInDimmer") {
                log.debug("Adding ${name} which is Zone ${zone}/Device ${device} with DNI ${dni}")
                d = addChildDevice("njschwartz", "Lutron Virtual Dimmer", dni, hubId, [
                    "label": "${name}",
                    "data": [
                        "dni": "${dni}",
                        "zone": "${zone}" ,
                        "deviceID": "${deviceID}",
                        "deviceType": "${deviceType}",
                        "hubIP": "${hubIP}"
                    ]
                ])
                d.refresh()
            } else if (deviceType == "WallSwitch") {
            	log.debug("Adding ${name} which is Zone ${zone}/Device ${device} with DNI ${dni}")
                d = addChildDevice("njschwartz", "Lutron Virtual Switch", dni, hubId, [
                    "label": "${name}",
                    "data": [
                        "dni": "${dni}",
                        "zone": "${zone}" ,
                        "device": "${device}",
                        "deviceType": "${deviceType}"
                    ]
                ])
                d.refresh()
           }
        }
    }
}

def addPicos() {
	def allPicos = getPicos()
	def name
    def device
    def deviceType
    def dni
    log.debug allPicos
    
    selectedPicos.each { id ->
    	log.debug id
        
        name = allPicos[id].name
        device = allPicos[id].deviceID 
        deviceType = allPicos[id].deviceType
 		dni = allPicos[id].dni.toString()
        def hubId = picos[id].hub
        
        // Check if child already exists
        def d = getAllChildDevices()?.find {
            it.device.deviceNetworkId == dni
        }
		

        if (!d) {
        	if (deviceType == "Pico3ButtonRaiseLower") {
                log.debug("Adding ${name} which is Device ${device} with DNI ${dni}")
                d = addChildDevice("njschwartz", "Lutron Pico", dni, hubId, [
                    "label": "${name}",
                    "data": [
                        "dni": dni,
                        "device": device,
                        "deviceType": "${deviceType}"
                    ]
                ])
               d.refresh()
            } else if (deviceType == "Pico2Button") {
            	log.debug("Adding ${name} which is Device ${device} with DNI ${dni}")
                d = addChildDevice("njschwartz", "Lutron Pico On/Off", dni, hubId, [
                    "label": "${name}",
                    "data": [
                        "dni": dni,
                        "device": device, 
                        "deviceType": deviceType
                    ]
                ])
               d.refresh()
            }
        //Call refresh on the new device to set the initial state
   	  }
   }
}

def addScenes() {
	def allScenes = getScenes()

	selectedScenes.each { id ->
        log.debug allScenes
        def name = allScenes[id].name
        def virtButton = allScenes[id].virtualButton
  
        // Make the dni the appId + virtubutton + the Lutron device virtual button number
 		def dni = allScenes[id].dni

        //add the dni to the switch state variable for future lookup
        allScenes[id].dni = dni

        // Check if child already exists
        def d = getAllChildDevices()?.find {
            it.device.deviceNetworkId == dni
        }
		def hubId = scenes[id].hub


        if (!d) {
            log.debug("Adding the scene ${name} with the DNI ${dni}")
            d = addChildDevice("njschwartz", "Lutron Scene", dni, hubId, [
                "label": "${name}",
                "data": [
                	"dni": dni,
                    "virtualButton": virtButton 
                ]
            ])
        }
    }
}

////////////////////////////////////////////////////////////////////////////
//						CHILD DEVICE FUNCTIONS							 //
///////////////////////////////////////////////////////////////////////////

//Parse the data from raspberry pi. This is called by the Raspberry Pi device type parse method because that device recieves all the updates sent from the pi back to the hub
def parse(description) {
    def dni
    def children = getAllChildDevices()
    
    if (description['Body']) {
    	log.debug('the desciption is ' + description)
            if(description['Body']['Device']) {
    	
        log.debug("Did I get here")
		
        def action = description['Body']['Action'].trim()
        if (action == 4 || action == "4") {
            return ""
        }
        def button = description['Body']['Button']
        def device = description['Body']['Device']
        log.debug "Device ${device} was used"
        def deviceType
        
        children.each { child ->
        	if (child.getDataValue("device".toString()) == device) {
        		dni = child.getDataValue("dni".toString())
                deviceType = child.getDataValue("deviceType".toString())
     		}	
        }
        
        if (dni != Null) {
        	if (deviceType == "Pico3ButtonRaiseLower") {
            	
            	
                switch (button) {
            	   case "2": button = 1
                    		break
                   case "3": button = 3
                    		break
                   case "4": button = 2
                    		break
                   case "5": button = 4
                    		break
                   case "6": button = 5
                    		break
                }
                log.debug "Button ${button} was pressed"
       	    	sendEvent(dni, [name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "button $button was pushed", isStateChange: true])
            } else if (deviceType == "Pico2Button") {
            	if (button == "2") {
                  button = 1
                } else {
                  button = 2
                }
                log.debug "Button ${button} was pressed"
                sendEvent(dni, [name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "button $button was pushed", isStateChange: true])
            }
        }
        return ""
    }
    
    
    
    if(description['Body']['Command'])
    	return ""

    
    //Get the zone and light level from the recieved message
     def zone = description['Body']['ZoneStatus']['Zone'].href.substring(6)
     def level = description['Body']['ZoneStatus'].Level
     //Match the zone to a child device and grab its DNI in order to send event to appropriate device
    children.each { child ->
        if (child.getDataValue("zone".toString()) == zone) {
        	dni = child.getDataValue("dni".toString())
     	}
    }
    
	if (level > 0) { 
    	sendEvent(dni, [name: "switch", value: "on"])
        sendEvent(dni, [name: "level", value: level])
    } else {
    	sendEvent(dni, [name: "switch", value: "off"])
    }
         
   
        
    }
    
    log.debug(description)
    
    if (description['device'] && description['level']) {
    	log.debug  "Got telnet data for update"
        def device = description['device']
        def level = description['level'].toInteger()
        def deviceType
        
        children.each { child ->
        	if (child.getDataValue("deviceID".toString()) == device.toString()) {
            	log.debug  "Matched"
        		dni = child.getDataValue("dni".toString())
                deviceType = child.getDataValue("deviceType".toString())
     		}	
        }
        log.debug dni
        if (dni != Null) {
			if (level > 0) { 
            	log.debug 'level greater than zero event'
    			sendEvent(dni, [name: "switch", value: "on"])
        		sendEvent(dni, [name: "level", value: level])
    		} else {
            	log.debug 'level off event'
    			sendEvent(dni, [name: "switch", value: "off"])
    		}
        }
    }

    if (description['device'] && description['button']) {
   		def button = description['button']
        def device = description['device']
        def action = description['action']
   		
        def deviceType
        
        children.each { child ->
        	if (child.getDataValue("device".toString()) == device.toString()) {
            	log.debug  "Matched"
        		dni = child.getDataValue("dni".toString())
                deviceType = child.getDataValue("deviceType".toString())
     		}	
        }
        log.debug dni
        if (dni != Null) {
        	if (deviceType == "Pico3ButtonRaiseLower") {
            	
            	
                switch (button) {
            	   case "2": button = 1
                    		break
                   case "3": button = 3
                    		break
                   case "4": button = 2
                    		break
                   case "5": button = 4
                    		break
                   case "6": button = 5
                    		break
                }
                log.debug "Button ${button} was ${action} on Pico ${device}"
       	    	//sendEvent(dni, [name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "button $button was pushed", isStateChange: true])
                sendEvent(dni, [name: "button", value: action, data: [buttonNumber: button, action: action], descriptionText: "button $button was pushed", isStateChange: true])
            } else if (deviceType == "Pico2Button") {
            	if (button == "2") {
                  button = 1
                } else {
                  button = 2
                }
                log.debug "Button ${button} was pressed"
                sendEvent(dni, [name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "button $button was pushed", isStateChange: true])
            }
        }
        return ""
    }
    
}

//Send request to turn light on (on assumes level of 100)
def on(childDevice) {

    def switches = getSwitches()
    
    def split = childDevice.device.deviceNetworkId.split("/")
    put("/on", switches[split[1]].zone, '100')
}

//Send refresh request to pi to get current status
def refresh(childDevice) {
    def switches = getSwitches()
    put("/status", switches[childDevice.device.deviceNetworkId].zone, "")
}

//Send request to turn light off (level 0)
def off(childDevice) {
    setLevel(childDevice, 0)
    //def switches = getSwitches()
    //put("/off", switches[childDevice.device.deviceNetworkId].zone, '0')
}

//Send request to set device to a specific level
def setLevel(childDevice, level) {
	log.debug('got request to setLevel from childDevice')
    def switches = getSwitches()
    put("/setLevel", switches[childDevice.device.deviceNetworkId].zone, switches[childDevice.device.deviceNetworkId].deviceID, level)
}

def setLevel(childDevice, level, rampRate) {
    def switches = getSwitches()
    put("/rampLevel", switches[childDevice.device.deviceNetworkId].device, level, rampRate)
}

def runScene(childDevice) {
	def scenes = getScenes()
    def buttonNum = scenes[childDevice.device.deviceNetworkId].virtualButton
    put("/scene", buttonNum)
}

//Function to send the request to pi
private put(path, zone, deviceID = "", level = "", rampRate= "") {
    def devices = getDevices()
    def ip
    def port
    devices.each {
    	ip = it.value.ip
        port = it.value.port
    }
    def hostHex = ip + ":" + port
	def content = [:]
    
    if (path == '/scene') {
      content.put('virtualButton', zone)
    } else {
        if (rampRate != "") {
            content.put("zone", zone)
            content.put("deviceID", deviceID)
            content.put("level", level.toString())
            content.put("rampRate", rampRate.toString())
        } else if (level != "") {
            content.put("zone", zone)
            content.put("deviceID", deviceID)
            content.put("level", level.toString())
        } else {
            content.put("zone", zone)
            content.put("deviceID", deviceID)
        }
    }
    
    def headers = [:] 
	headers.put("HOST", hostHex)
	headers.put("Content-Type", "application/json")
    
    def result = new physicalgraph.device.HubAction(
        method: "POST",
        path: path,
        body: content,
        headers: headers
    )

	sendHubCommand(result)
}



private String makeNetworkId(ipaddr, port) { 
     String hexIp = ipaddr.tokenize('.').collect { 
     String.format('%02X', it.toInteger()) 
     }.join() 
     String hexPort = String.format('%04X', port.toInteger()) 
     log.debug "${hexIp}:${hexPort}" 
     return "${hexIp}:${hexPort}" 
}

def subscribeToDevices() {
    log.debug "subscribeToDevices() called"
    def devices = getAllChildDevices()
    devices.each { d ->
        d.subscribe()
    }
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private def parseDiscoveryMessage(String description) {
    def device = [:]
    def parts = description.split(',')
    parts.each { part ->
        part = part.trim()
        if (part.startsWith('devicetype:')) {
            def valueString = part.split(":")[1].trim()
            device.devicetype = valueString
        } else if (part.startsWith('mac:')) {
            def valueString = part.split(":")[1].trim()
            if (valueString) {
                device.mac = valueString
            }
        } else if (part.startsWith('networkAddress:')) {
            def valueString = part.split(":")[1].trim()
            if (valueString) {
                device.ip = valueString
            }
        } else if (part.startsWith('deviceAddress:')) {
            def valueString = part.split(":")[1].trim()
            if (valueString) {
                device.port = valueString
            }
        } else if (part.startsWith('ssdpPath:')) {
            def valueString = part.split(":")[1].trim()
            if (valueString) {
                device.ssdpPath = valueString
            }
        } else if (part.startsWith('ssdpUSN:')) {
            part -= "ssdpUSN:"
            def valueString = part.trim()
            if (valueString) {
                device.ssdpUSN = valueString
            }
        } else if (part.startsWith('ssdpTerm:')) {
            part -= "ssdpTerm:"
            def valueString = part.trim()
            if (valueString) {
                device.ssdpTerm = valueString
            }
        } else if (part.startsWith('headers')) {
            part -= "headers:"
            def valueString = part.trim()
            if (valueString) {
                device.headers = valueString
            }
        } else if (part.startsWith('body')) {
            part -= "body:"
            def valueString = part.trim()
            if (valueString) {
                device.body = valueString
            }
        }
    }

    device
}