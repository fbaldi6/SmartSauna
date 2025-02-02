package it.unipi.dii.inginf.iot.smartsauna.mqtt;

import com.google.gson.Gson;
import it.unipi.dii.inginf.iot.smartsauna.log.Logger;
import it.unipi.dii.inginf.iot.smartsauna.model.HumiditySample;
import it.unipi.dii.inginf.iot.smartsauna.model.TemperatureSample;
import it.unipi.dii.inginf.iot.smartsauna.mqtt.devices.humidity.HumidityCollector;
import it.unipi.dii.inginf.iot.smartsauna.mqtt.devices.temperature.TemperatureCollector;
import org.eclipse.paho.client.mqttv3.*;

public class MQTTHandler implements MqttCallback {

    private final String BROKER = "tcp://127.0.0.1:1883";
    private final String CLIENT_ID = "SmartSaunaCollector";
    private final int SECONDS_TO_WAIT_FOR_RECONNECTION = 5;
    private final int MAX_RECONNECTION_ITERATIONS = 10;

    private MqttClient mqttClient = null;
    private Gson parser;
    private HumidityCollector humidityCollector;
    private TemperatureCollector temperatureCollector;

    private Logger logger;

    public MQTTHandler ()
    {
        parser = new Gson();
        logger = Logger.getInstance();
        humidityCollector = new HumidityCollector();
        temperatureCollector = new TemperatureCollector();
        do {
            try {
                mqttClient = new MqttClient(BROKER, CLIENT_ID);
                System.out.println("Connecting to the broker: " + BROKER);
                mqttClient.setCallback( this );
                connectToBroker();
            }
            catch(MqttException me)
            {
                System.out.println("I could not connect, Retrying ...");
            }
        }while(!mqttClient.isConnected());
    }

    /**
     * This function is used to try to connect to the broker
     */
    private void connectToBroker () throws MqttException {
        mqttClient.connect();
        mqttClient.subscribe(humidityCollector.HUMIDITY_TOPIC);
        System.out.println("Subscribed to: " + humidityCollector.HUMIDITY_TOPIC);
        mqttClient.subscribe(temperatureCollector.TEMPERATURE_TOPIC);
        System.out.println("Subscribed to: " + temperatureCollector.TEMPERATURE_TOPIC);
    }

    /**
     * Function used to publish a message
     * @param topic     topic of the message
     * @param message   message to send
     */
    public void publishMessage (final String topic, final String message)
    {
        try
        {
            mqttClient.publish(topic, new MqttMessage(message.getBytes()));
        }
        catch(MqttException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void connectionLost(Throwable throwable) {
        System.out.println("Connection with the Broker lost!");
        // We have lost the connection, we have to try to reconnect after waiting some time
        // At each iteration we increase the time waited
        int iter = 0;
        do {
            iter++; // first iteration iter=1
            if (iter > MAX_RECONNECTION_ITERATIONS)
            {
                System.err.println("Reconnection with the broker not possible!");
                System.exit(-1);
            }
            try
            {
                Thread.sleep(SECONDS_TO_WAIT_FOR_RECONNECTION * 1000 * iter);
                System.out.println("New attempt to connect to the broker...");
                connectToBroker();
            }
            catch (MqttException | InterruptedException e)
            {
                e.printStackTrace();
            }
        } while (!this.mqttClient.isConnected());
        System.out.println("Connection with the Broker restored!");
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        String payload = new String(mqttMessage.getPayload());
        if (topic.equals(humidityCollector.HUMIDITY_TOPIC))
        {
            HumiditySample humiditySample = parser.fromJson(payload, HumiditySample.class);
            humidityCollector.addHumiditySample(humiditySample);
            float newAverage = humidityCollector.getAverage();
            float midRange = humidityCollector.getMidRange();
            if (newAverage < (humidityCollector.getLowerBoundHumidity() + (midRange - humidityCollector.getLowerBoundHumidity())/2))
            {
                if (!humidityCollector.getLastCommand().equals(humidityCollector.INC))
                {
                    logger.logHumidity("Average level of Humidity too low: " + newAverage + "%, increase it");
                    publishMessage(humidityCollector.HUMIDIFIER_TOPIC, humidityCollector.INC);
                    humidityCollector.setLastCommand(humidityCollector.INC);
                }
                else
                    logger.logHumidity("Average level of Humidity too low: " + newAverage + "%, but is increasing");
            }
            else if (newAverage > (humidityCollector.getUpperBoundHumidity() - (humidityCollector.getUpperBoundHumidity() - midRange)/2))
            {
                if (!humidityCollector.getLastCommand().equals(humidityCollector.DEC))
                {
                    logger.logHumidity("Average level of Humidity too high: " + newAverage + "%, decrease it");
                    publishMessage(humidityCollector.HUMIDIFIER_TOPIC, humidityCollector.DEC);
                    humidityCollector.setLastCommand(humidityCollector.DEC);
                }
                else
                    logger.logHumidity("Average level of Humidity too high: " + newAverage + "%, but is decreasing");
            }
            else
            {
                if (!humidityCollector.getLastCommand().equals(humidityCollector.OFF))
                {
                    logger.logHumidity("Correct average humidity level: " + newAverage + "%, switch off the humidifier/dehumidifier");
                    publishMessage(humidityCollector.HUMIDIFIER_TOPIC, humidityCollector.OFF);
                    humidityCollector.setLastCommand(humidityCollector.OFF);
                }
                else
                {
                    logger.logHumidity("Correct average humidity level: " + newAverage + "%");
                }
            }
        }
        else if (topic.equals(temperatureCollector.TEMPERATURE_TOPIC))
        {
            TemperatureSample temperatureSample = parser.fromJson(payload, TemperatureSample.class);
            temperatureCollector.addTemperatureSample(temperatureSample);
            float newAverage = temperatureCollector.getAverage();
            float midRange = temperatureCollector.getMidRange();
            if (newAverage < (temperatureCollector.getLowerBoundTemperature() + (midRange - temperatureCollector.getLowerBoundTemperature())/2))
            {
                if (!temperatureCollector.getLastCommand().equals(temperatureCollector.INC))
                {
                    logger.logTemperature("Average level of temperature too low: " + newAverage + "°C, increase it");
                    publishMessage(temperatureCollector.AC_TOPIC, temperatureCollector.INC);
                    temperatureCollector.setLastCommand(temperatureCollector.INC);
                }
                else
                    logger.logTemperature("Average level of temperature too low: " + newAverage + "°C, but is increasing");
            }
            else if (newAverage > (temperatureCollector.getUpperBoundTemperature() - (temperatureCollector.getUpperBoundTemperature() - midRange)/2))
            {
                if (!temperatureCollector.getLastCommand().equals(temperatureCollector.DEC))
                {
                    logger.logTemperature("Average level of temperature too high: " + newAverage + "°C, decrease it");
                    publishMessage(temperatureCollector.AC_TOPIC, temperatureCollector.DEC);
                    temperatureCollector.setLastCommand(temperatureCollector.DEC);
                }
                else
                    logger.logTemperature("Average level of temperature too high: " + newAverage + "°C, but is decreasing");
            }
            else
            {
                if (!temperatureCollector.getLastCommand().equals(temperatureCollector.OFF))
                {
                    logger.logTemperature("Correct average temperature level: " + newAverage +"°C, switch off the AC");
                    publishMessage(temperatureCollector.AC_TOPIC, temperatureCollector.OFF);
                    temperatureCollector.setLastCommand(temperatureCollector.OFF);
                }
                else
                    logger.logTemperature("Correct average temperature level: " + newAverage + "°C");
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
        logger.logInfo("Message correctly delivered");
    }

    public HumidityCollector getHumidityCollector() {
        return humidityCollector;
    }

    public TemperatureCollector getTemperatureCollector() {
        return temperatureCollector;
    }
}
