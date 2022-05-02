No longer in use

# SYFO Sykmelding SYFO MQ 
This project contains application that reads mq messages for syfoservice in skygge-prod


# Technologies used
* Kotlin
* Ktor
* Gradle
* Spek
* Jackson


### Building the application
#### Compile and package application
To build locally and run the integration tests you can simply run `./gradlew shadowJar` or  on windows 
`gradlew.bat shadowJar`

#### Creating a docker image
Creating a docker image should be as simple as `docker build -t syfosmsyfoservice .`

## Contact us
### Code/project related questions can be sent to
* Kevin Sillerud, `kevin.sillerud@nav.no`
* Joakim Kartveit, `joakim.kartveit@nav.no`

### For NAV employees
We are available at the Slack channel #barken
