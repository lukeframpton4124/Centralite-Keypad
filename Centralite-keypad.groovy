/**
 *  Centralite Keypad
 *
 *  Copyright 2015 Mitch Pond
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
metadata {
	definition (name: "Centralite Keypad", namespace: "mitchpond", author: "Mitch Pond") {
		capability "Battery"
		capability "Configuration"
		capability "Sensor"
		capability "Temperature Measurement"
		capability "Refresh"
		capability "Lock Codes"
		capability "Tamper Alert"
		capability "Tone"
        capability "button"
		
		attribute "armMode", "String"
		
		command "setDisarmed"
		command "setArmedAway"
		command "setArmedStay"
		command "setArmedNight"
        command "setExitDelay", ['number']
        command "setEntryDelay", ['number']
		command "testCmd"
		command "sendInvalidKeycodeResponse"
		command "acknowledgeArmRequest"
		
		fingerprint endpointId: "01", profileId: "0104", deviceId: "0401", inClusters: "0000,0001,0003,0020,0402,0500,0B05", outClusters: "0019,0501", manufacturer: "CentraLite", model: "3400", deviceJoinName: "Xfinity 3400-X Keypad"
		fingerprint endpointId: "01", profileId: "0104", deviceId: "0401", inClusters: "0000,0001,0003,0020,0402,0500,0501,0B05,FC04", outClusters: "0019,0501", manufacturer: "CentraLite", model: "3405-L", deviceJoinName: "Iris 3405-L Keypad"
	}
	
	preferences{
		input ("tempOffset", "number", title: "Enter an offset to adjust the reported temperature",
				defaultValue: 0, displayDuringSetup: false)
		input ("beepLength", "number", title: "Enter length of beep in seconds",
				defaultValue: 3, displayDuringSetup: false)
	}

	tiles {
		valueTile("battery", "device.battery", decoration: "flat") {
			state "battery", label:'${currentValue}% battery', unit:""
		}
		valueTile("temperature", "device.temperature") {
			state "temperature", label: '${currentValue}°',
				backgroundColors:[
						[value: 31, color: "#153591"],
						[value: 44, color: "#1e9cbb"],
						[value: 59, color: "#90d2a7"],
						[value: 74, color: "#44b621"],
						[value: 84, color: "#f1d801"],
						[value: 95, color: "#d04e00"],
						[value: 96, color: "#bc2323"]
					]
		}
		valueTile("armMode", "device.armMode", decoration: "flat") {
			state "armMode", label: '${currentValue}'
		}
		standardTile("refresh", "device.refresh", decoration: "flat", width: 1, height: 1) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}
		standardTile("configure", "device.configure", decoration: "flat", width: 1, height: 1) {
			state "default", action:"configuration.configure", icon:"st.secondary.configure"
		}
		standardTile("beep", "device.beep", decoration: "flat", width: 1, height: 1) {
			state "default", action:"tone.beep", icon:"st.secondary.beep", backgroundColor:"#ffffff"
		}
		standardTile("test", "device.testCmd", decoration: "flat", width: 1, height: 1) {
			state "default", action:"testCmd", icon:"st.secondary.tools", label: "Test", backgroundColor:"#ffffff"
		}
		main ("battery")
		//TODO: armMode is in here for debug purposes. Remove later.
		details (["temperature","battery","armMode","configure","refresh","beep","test"])
	}
}

// parse events into attributes
def parse(String description) {
	log.debug "Parsing '${description}'";
	def results = [];
	
	//------Miscellaneous Zigbee message------//
	if (description?.startsWith('catchall:')) {
		//log.debug zigbee.parse(description);
		def message = zigbee.parse(description);
		
		//------Profile-wide command (rattr responses, errors, etc.)------//
		if (message?.isClusterSpecific == false) {
			//------Default response------//
			if (message?.command == 0x0B) {
				if (message?.data[1] == 0x81) 
					log.error "Device: unrecognized command: "+description;
				else if (message?.data[1] == 0x80) 
					log.error "Device: malformed command: "+description;
			}
			//------Read attributes responses------//
			else if (message?.command == 0x01) {
				if (message?.clusterId == 0x0402) {
					log.debug "Device: read attribute response: "+description;
					results = parseTempAttributeMsg(message)
				}}
			else 
				log.debug "Unhandled profile-wide command: "+description;
		}
		//------Cluster specific commands------//
		else if (message?.isClusterSpecific) {
			//------IAS ACE------//
			if (message?.clusterId == 0x0501) {
				if (message?.command == 0x07) {
				//---------------------------------------//
				//Not sure what the device is doing here. It doesn't look like an ACE client should be sending this.
				//Plus, the command isn't sent with a payload which doesn't seem to follow the spec.
				//I'm assuming that they're using it as a sort of heartbeat (??)
				// *** This does not correlate to motion events ***
				//---------------------------------------//
					log.debug "${device.displayName} awake and requesting status"
					results = sendStatusToDevice();
					log.trace results
				}
                else if (message?.command == 0x04) {
                	results = createEvent(name: "button", value: "pushed", data: [buttonNumber: 1], descriptionText: "$device.displayName panic button was pushed", isStateChange: true)
                }
				else if (message?.command == 0x00) {
					results = handleArmRequest(message)
					log.trace results
				}
			}
			else log.debug "Unhandled cluster-specific command: "+description
		}
	}
	//------IAS Zone Enroll request------//
	else if (description?.startsWith('enroll request')) {
		log.debug "Sending IAS enroll response..."
		results = zigbee.enrollResponse()
	}
	//------Read Attribute response------//
	else if (description?.startsWith('read attr -')) {
		results = parseReportAttributeMessage(description)
	}
	//------Temperature Report------//
	else if (description?.startsWith('temperature: ')) {
		log.debug "Got ST-style temperature report.."
		results = createEvent(getTemperatureResult(zigbee.parseHATemperatureValue(description, "temperature: ", getTemperatureScale())))
		log.debug results
	}
    else if (description?.startsWith('zone status ')) {
    	results = parseIasMessage(description)
    }
	return results
}

def configure() {
	log.debug "Configure called for device ${device.displayName}."
	String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
    
	def cmd = [
    	"zcl global write 0x500 0x10 0xf0 {${zigbeeEui}}", "delay 200",
		"send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 1000",
		//------Set up binding------//
		"zdo bind 0x${device.deviceNetworkId} ${endpointId} 1 0x0001 {${device.zigbeeId}} {}", "delay 200",
		"zdo bind 0x${device.deviceNetworkId} ${endpointId} 1 0x0500 {${device.zigbeeId}} {}", "delay 200",
		"zdo bind 0x${device.deviceNetworkId} ${endpointId} 1 0x0501 {${device.zigbeeId}} {}", "delay 200"
	] +
	zigbee.configureReporting(1,0x20,0x20,3600,43200,0x01) + 		//battery reporting
	zigbee.configureReporting(0x0402,0x00,0x29,30,3600,0x0064)		//temperature reporting  
	
	return cmd + refresh()
}

def refresh() {
	 return sendStatusToDevice() +
			zigbee.readAttribute(0x0001,0x20) + 
			zigbee.readAttribute(0x0402,0x00)
}

private parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	//log.debug "Desc Map: $descMap"

	def results = []
	
	if (descMap.cluster == "0001" && descMap.attrId == "0020") {
		log.debug "Received battery level report"
		results = createEvent(getBatteryResult(Integer.parseInt(descMap.value, 16)))
	}
	else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
		def value = getTemperature(descMap.value)
		results = createEvent(getTemperatureResult(value))
	}

	return results
}

private parseTempAttributeMsg(message) {
	byte[] temp = message.data[-2..-1].reverse()
	createEvent(getTemperatureResult(getTemperature(temp.encodeHex() as String)))
}

private Map parseIasMessage(String description) {
    List parsedMsg = description.split(' ')
    String msgCode = parsedMsg[2]
    
    Map resultMap = [:]
    switch(msgCode) {
        case '0x0020': // Closed/No Motion/Dry
        	resultMap = getContactResult('closed')
            break

        case '0x0021': // Open/Motion/Wet
        	resultMap = getContactResult('open')
            break

        case '0x0022': // Tamper Alarm
            break

        case '0x0023': // Battery Alarm
            break

        case '0x0024': // Supervision Report
        	resultMap = getContactResult('closed')
            break

        case '0x0025': // Restore Report
        	resultMap = getContactResult('open')
            break

        case '0x0026': // Trouble/Failure
            break

        case '0x0028': // Test Mode
            break
        case '0x0000':
			resultMap = createEvent(name: "tamper", value: "cleared", isStateChange: true, displayed: false)
            break
        case '0x0004':
			resultMap = createEvent(name: "tamper", value: "detected", isStateChange: true, displayed: false)
            break;
        default:
        	log.debug "Invalid message code in IAS message: ${msgCode}"
    }
    return resultMap
}

//TODO: find actual good battery voltage range and update this method with proper values for min/max
//
//Converts the battery level response into a percentage to display in ST
//and creates appropriate message for given level

private getBatteryResult(rawValue) {
	def linkText = getLinkText(device)

	def result = [name: 'battery']

	def volts = rawValue / 10
	def descriptionText
	if (volts > 3.5) {
		result.descriptionText = "${linkText} battery has too much power (${volts} volts)."
	}
	else {
		def minVolts = 2.1
		def maxVolts = 3.0
		def pct = (volts - minVolts) / (maxVolts - minVolts)
		result.value = Math.min(100, (int) pct * 100)
		result.descriptionText = "${linkText} battery was ${result.value}%"
	}

	return result
}

private getTemperature(value) {
	def celcius = Integer.parseInt(value, 16).shortValue() / 100
	if(getTemperatureScale() == "C"){
		return celcius
	} else {
		return celsiusToFahrenheit(celcius) as Integer
	}
}

private Map getTemperatureResult(value) {
	log.debug 'TEMP'
	def linkText = getLinkText(device)
	if (tempOffset) {
		def offset = tempOffset as int
		def v = value as int
		value = v + offset
	}
	def descriptionText = "${linkText} was ${value}°${temperatureScale}"
	return [
		name: 'temperature',
		value: value,
		descriptionText: descriptionText
	]
}

//------Command handlers------//
private handleArmRequest(message){
	def keycode = new String(message.data[2..-2] as byte[],'UTF-8')
	def reqArmMode = message.data[0]
	//state.lastKeycode = keycode
	log.debug "Received arm command with keycode/armMode: ${keycode}/${reqArmMode}"

	//Acknowledge the command. This may not be *technically* correct, but it works
	/*List cmds = [
				 "raw 0x501 {09 01 00 0${reqArmMode}}", "delay 200",
				 "send 0x${device.deviceNetworkId} 1 1", "delay 500"
				]
	def results = cmds?.collect { new physicalgraph.device.HubAction(it) } + createCodeEntryEvent(keycode, reqArmMode)
	*/
	def results = createCodeEntryEvent(keycode, reqArmMode)
	log.trace "Method: handleArmRequest(message): "+results
	return results
}

def createCodeEntryEvent(keycode, armMode) {
	createEvent(name: "codeEntered", value: keycode as String, data: armMode as String, 
				isStateChange: true, displayed: false)
}

//
//The keypad seems to be expecting responses that are not in-line with the HA 1.2 spec. Maybe HA 1.3 or Zigbee 3.0??
//
private sendStatusToDevice() {
	log.debug 'Sending status to device...'
	def armMode = device.currentValue("armMode")
	log.trace 'Arm mode: '+armMode
	def status = ''
	if (armMode == 'disarmed') status = 0
	else if (armMode == 'armedAway') status = 3
	else if (armMode == 'armedStay') status = 1
	else if (armMode == 'armedNight') status = 2
	
	// If we're not in one of the 4 basic modes, don't update the status, don't want to override beep timings, exit delay is dependent on it being correct
	if (status != '')
	{
		return sendRawStatus(status)
	}
}


// Statuses:
// 00 - Disarmed
// 01 - Armed partial
// 02 - Armed partial
// 03 - Armed Away
// 04 - ?
// 05 - Fast beep (1 per second)
// 05 - Entry delay (Uses seconds) Appears to keep the status lights as it was
// 06 - Amber status blink (Ignores seconds)
// 07 - ?
// 08 - Red status blink
// 09 - ?
// 10 - Exit delay Slow beep (2 per second, accelerating to 1 beep per second for the last 10 seconds) - With red flashing status - Uses seconds
// 11 - ?
// 12 - ?
// 13 - ?

private sendRawStatus(status, seconds = 00) {
	log.debug "Sending Status ${zigbee.convertToHexString(status)}${zigbee.convertToHexString(seconds)} to device..."
    
    List cmds = ["raw 0x501 {09 01 04 ${zigbee.convertToHexString(status)}${zigbee.convertToHexString(seconds)}}",
    			 "send 0x${device.deviceNetworkId} 1 1", 'delay 100']
                 
    def results = cmds?.collect { new physicalgraph.device.HubAction(it) };
    return results
}

def notifyPanelStatusChanged(status) {
	//TODO: not yet implemented. May not be needed.
}
//------------------------//

def setDisarmed() { setModeHelper("disarmed",0) }
def setArmedAway(def delay=0) { setModeHelper("armedAway",delay) }
def setArmedStay(def delay=0) { setModeHelper("armedStay",delay) }
def setArmedNight(def delay=0) { setModeHelper("armedNight",delay) }

def setEntryDelay(delay=30) {
	setModeHelper("entryDelay", delay)
	sendRawStatus(5, delay) // Entry delay beeps
	sendRawStatus(8, 0)     // Flashing red status?
}

def setExitDelay(delay=30) {
	setModeHelper("exitDelay", delay)
	sendRawStatus(10, delay)  // Exit delay
}

private setModeHelper(String armMode, delay) {
	sendEvent([name: "armMode", value: armMode, data: [delay: delay as int], isStateChange: true])
	sendStatusToDevice()
}

private setKeypadArmMode(armMode){
	Map mode = [disarmed: '00', armedAway: '03', armedStay: '01', armedNight: '02', entryDelay: '', exitDelay: '']
    if (mode[armMode] != '')
    {
		return ["raw 0x501 {09 01 04 ${mode[armMode]}00}",
				 "send 0x${device.deviceNetworkId} 1 1", 'delay 100']
    }
}

def acknowledgeArmRequest(armMode){
	List cmds = [
				 "raw 0x501 {09 01 00 0${armMode}}",
				 "send 0x${device.deviceNetworkId} 1 1", "delay 100"
				]
	def results = cmds?.collect { new physicalgraph.device.HubAction(it) }
	log.trace "Method: acknowledgeArmRequest(armMode): "+results
	return results
}

def sendInvalidKeycodeResponse(){
	List cmds = [
				 "raw 0x501 {09 01 00 04}",
				 "send 0x${device.deviceNetworkId} 1 1", "delay 100"
				]
				 
	log.trace 'Method: sendInvalidKeycodeResponse(): '+cmds
	return (cmds?.collect { new physicalgraph.device.HubAction(it) }) + sendStatusToDevice()
}

def beep(def beepLength = settings.beepLength) {
	def len = zigbee.convertToHexString(beepLength, 2)
	List cmds = ["raw 0x501 {09 01 04 05${len}}", 'delay 200',
				 "send 0x${device.deviceNetworkId} 1 1", 'delay 500']
	cmds
}

//------Utility methods------//

private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
	int i = 0;
	int j = array.length - 1;
	byte tmp;
	while (j > i) {
		tmp = array[j];
		array[j] = array[i];
		array[i] = tmp;
		j--;
		i++;
	}
	return array
}
//------------------------//

private testCmd(){
	//log.trace zigbee.parse('catchall: 0104 0501 01 01 0140 00 4F2D 01 00 0000 07 00 ')
	beep(10)
	//test exit delay
	//log.debug device.zigbeeId
	//testingTesting()
	//discoverCmds()
	//zigbee.configureReporting(1,0x20,0x20,3600,43200,0x01)		//battery reporting
	//["raw 0x0001 {00 00 06 00 2000 20 100E FEFF 01}",
	//"send 0x${device.deviceNetworkId} 1 1"]
	//zigbee.command(0x0003, 0x00, "0500") //Identify: blinks connection light
}

private discoverCmds(){
	List cmds = ["raw 0x0501 {08 01 11 0011}", 'delay 200',
				 "send 0x${device.deviceNetworkId} 1 1", 'delay 500']
	cmds
}

private testingTesting() {
	log.debug "Delay: "+device.currentState("armMode").toString()
	List cmds = ["raw 0x501 {09 01 04 050A}", 'delay 200',
				 "send 0x${device.deviceNetworkId} 1 1", 'delay 500']
	cmds
}
