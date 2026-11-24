FROM docker:28.5.1-cli
RUN apk add --no-cache bash python3 util-linux
COPY fake-aws.py /usr/local/bin/aws
RUN chmod +x /usr/local/bin/aws
