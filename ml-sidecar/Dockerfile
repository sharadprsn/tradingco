FROM python:3.11-alpine

WORKDIR /app

RUN apk add --no-cache libgfortran libgomp

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && \
    rm -rf /usr/local/lib/python3.11/site-packages/pip* \
           /usr/local/lib/python3.11/site-packages/setuptools* \
           /usr/local/lib/python3.11/site-packages/wheel* \
           /usr/local/lib/python3.11/ensurepip \
           /usr/local/bin/pip* \
           /usr/local/bin/python3.11-config

RUN python -c "import nltk; nltk.download('vader_lexicon')"

COPY main.py features.py train.py sentiment.py .

RUN mkdir -p /app/models

ENV OMP_NUM_THREADS=1

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
