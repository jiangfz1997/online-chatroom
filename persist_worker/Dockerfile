FROM golang:1.24.1

WORKDIR /app
COPY . .

RUN go build -o persist_worker .

CMD ["./persist_worker"]