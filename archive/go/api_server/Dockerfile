
FROM golang:1.24.1 as builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o api_server .

FROM alpine:latest
WORKDIR /root/

COPY --from=builder /app/api_server .

EXPOSE 8080

CMD ["./api_server"]