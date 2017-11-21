###
# The directory of the Dockerfile should contain your 'hostname' and 'private_key' files.
# In the docker-compose.yml file you can pass the ONION_ADDRESS referenced below.
###

# pull base image
FROM openjdk:8-jdk

RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    vim \
    fakeroot \
    sudo \
    python \
    tor \
    torsocks \
    python-dev \
    build-essential \
    netcat \
    python-pip && rm -rf /var/lib/apt/lists/*

RUN git clone https://github.com/mrosseel/bisq-uptime.git
WORKDIR /bisq-uptime/
#RUN git checkout Development
RUN mvn clean install

COPY start_tor.sh ./
RUN  chmod +x *.sh
WORKDIR ./src/main/python
RUN pip install --upgrade setuptools && pip install -r requirements.txt
WORKDIR /bisq-uptime/

CMD ./start_tor.sh && java -cp ./target/bisq-uptime*.jar io.bisq.uptime.Uptime
#CMD tail -f /dev/null
