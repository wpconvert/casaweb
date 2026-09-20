import asyncio
import json
import websockets


# daftar koneksi APK
clients = {}

# daftar HP yang terdaftar
phones = {}


async def send_all(data):
    message = json.dumps(data)

    dead = []

    for phone_id, ws in clients.items():
        try:
            await ws.send(message)
        except:
            dead.append(phone_id)

    for phone_id in dead:
        clients.pop(phone_id, None)



async def broadcast_phones():

    await send_all({
        "type": "phones",
        "phones": phones
    })



async def handler(websocket):

    phone_id = None

    print("Connection masuk")


    try:

        async for message in websocket:

            try:
                data = json.loads(message)

            except:
                continue


            msg_type = data.get("type")


            # APK pertama connect
            if msg_type == "register":

                phone_id = data.get("id")


                if not phone_id:
                    continue


                clients[phone_id] = websocket


                phones[phone_id] = {
                    "id": phone_id,
                    "name": data.get(
                        "name",
                        "Android"
                    ),
                    "model": data.get(
                        "model",
                        "Unknown"
                    ),
                    "online": True
                }


                print(
                    "PHONE ONLINE:",
                    phone_id,
                    phones[phone_id]["model"]
                )


                await broadcast_phones()



            # heartbeat dari APK
            elif msg_type == "ping":

                if phone_id in phones:
                    phones[phone_id]["online"] = True



            # update info HP
            elif msg_type == "update":

                if phone_id in phones:

                    phones[phone_id].update(
                        data.get("data", {})
                    )

                    await broadcast_phones()



    except Exception as e:

        print(
            "Connection error:",
            e
        )


    finally:

        if phone_id:

            print(
                "PHONE OFFLINE:",
                phone_id
            )


            clients.pop(
                phone_id,
                None
            )


            if phone_id in phones:

                phones[phone_id]["online"] = False


            await broadcast_phones()



async def main():

    print(
        "Web Phone Server :8765"
    )


    async with websockets.serve(
        handler,
        "0.0.0.0",
        8765
    ):

        await asyncio.Future()



if __name__ == "__main__":

    asyncio.run(main())
