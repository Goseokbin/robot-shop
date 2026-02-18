FROM python:3.9

EXPOSE 8080
USER root

ENV INSTANA_SERVICE_NAME=payment

WORKDIR /app

# pyrasite-dump용: gdb (pyrasite 의존성)
# objgraph용: graphviz (show_refs, show_backrefs 시각화)
RUN apt-get update && apt-get install -y --no-install-recommends gdb graphviz \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/

RUN pip install -r requirements.txt

COPY *.py /app/
COPY payment.ini /app/

#CMD ["python", "payment.py"]
CMD ["uwsgi", "--ini", "payment.ini"]

