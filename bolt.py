import os
from slack_bolt import App
from slack_bolt.adapter.aws_lambda import SlackRequestHandler
from slack_sdk import WebClient
from slack_bolt.adapter.socket_mode import SocketModeHandler
import pandas as pd
import re
import openai
import nltk
from nltk.corpus import stopwords
nltk.download('stopwords')

openai.api_key = "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"
# Set up the model
model_engine = "text-davinci-003"

df_seo = pd.read_csv('SEO FAQs.csv')
#df_seo = pd.read_csv('/Users/kennethcheong/Documents/GitHub/mo_kenneth_codes/chatbot/SEO FAQs.csv')
df_seo = df_seo.astype('string')
df_seo['tokenized'] = df_seo['question'].str.split(' ')
for index, row in df_seo.iterrows():
    list_words = []
    for word in row['tokenized']:
        list_words.append(word.lower())
    df_seo.at[index,'tokenized'] = list_words

# Initializes your Bolt app with a bot token and signing secret
app = App(
    token="xoxb-1171023480869-3427732797893-Ef6ujQiTiQ3Y3M4RhPMPr9bS",
    signing_secret="a3414014f1ac6a6456558dd998251f92"
)

client = WebClient(token='xoxb-1171023480869-3427732797893-Ef6ujQiTiQ3Y3M4RhPMPr9bS')

@app.message(re.compile("(hello|hey)"))
def message_hello(message, say):
    # say() sends a message to the channel where the event was triggered
    say(
        blocks=[
            {
                "type": "section",
                "text": {"type": "mrkdwn", "text": f"Hey there <@{message['user']}>! You can ask me questions by putting 'ask' before your question. For example, 'ask Why is there a sudden drop in rankings?"}
            }
        ],
        text=f"Hey there <@{message['user']}>!"
    )

@app.message(re.compile("\Aask"))
def reply_in_thread(payload: dict):
    text = payload.get('text')
    query = text.lstrip('ask ').lower()
    query = query.split(' ')

    #remove stopwords
    query = [stopword for stopword in query if stopword not in stopwords.words('english')]
    
    for index, row in df_seo.iterrows():
        df_seo.at[index, 'score'] = len([x for x in query if x in row['tokenized']])
    df_result = df_seo.astype({'score': 'int32'})
    df_result_final = df_result[df_result['score']>0].sort_values(by=['score'],ascending=False)
    if len(df_result_final)>0:
        for index,row in df_result_final.iloc[0:3,].iterrows():
            response = client.chat_postMessage(channel=payload.get('channel'),
                                               #thread_ts=payload.get('ts'),
                                               text=row['question'])
            response = client.chat_postMessage(channel=payload.get('channel'),
                                               #thread_ts=payload.get('ts'),
                                               text=row['answer'])
            response = client.chat_postMessage(channel=payload.get('channel'),
                                               #thread_ts=payload.get('ts'),
                                               text='\n')
        if len(df_result_final) == 1:
            response = client.chat_postMessage(channel=payload.get('channel'),
            #thread_ts=payload.get('ts'),
            text='\n',
            attachments=[
            {"text": "Does this answer your query?",
            "color": "#3AA3E3",
            "attachment_type": "default",
            "callback_id": "best_answer",
            "actions": [
            {"name": "games_list",
            "text": "Pick one",
            "type": "select",
            "options": [
            {"text": "Yes",
            "value": df_result_final.iloc[0,1]
            }]}]}])
            
        elif len(df_result_final) == 2:
            response = client.chat_postMessage(channel=payload.get('channel'),
            #thread_ts=payload.get('ts'),
            text='\n',
            attachments=[
            {"text": "Pick the best bot answer.",
            "color": "#3AA3E3",
            "attachment_type": "default",
            "callback_id": "best_answer",
            "actions": [
            {"name": "games_list",
            "text": "Pick one",
            "type": "select",
            "options": [{"text": df_result_final.iloc[0,1],
            "value": df_result_final.iloc[0,1]
            },
            {"text": df_result_final.iloc[1,1],
            "value": df_result_final.iloc[1,1]
            }]}]}])

        else:
            response = client.chat_postMessage(channel=payload.get('channel'),
            #thread_ts=payload.get('ts'),
            text='\n',
            attachments=[
            {"text": "Pick the best bot answer.",
            "color": "#3AA3E3",
            "attachment_type": "default",
            "callback_id": "best_answer",
            "actions": [
            {"name": "games_list",
            "text": "Pick one",
            "type": "select",
            "options": [{"text": df_result_final.iloc[0,1],
            "value": df_result_final.iloc[0,1]
            },
            {"text": df_result_final.iloc[1,1],
            "value": df_result_final.iloc[1,1]
            },
            {"text": df_result_final.iloc[2,1],
            "value": df_result_final.iloc[2,1]
            }]}]}])

    elif len(df_result_final) == 0:
        response = client.chat_postMessage(channel=payload.get('channel'),
                                               #thread_ts=payload.get('ts'),
                                               text="I'm sorry, I don't have the answer to that right now.",
        attachments=[{
            "fallback": "Would you like me to get an answer from ChatGPT?",
            "title": "Would you like me to get an answer from ChatGPT?",
            "callback_id": "ask_gpt_instead",
            "color": "#3AA3E3",
            "attachment_type": "default",
            "actions": [
                {
                    "name": "yes",
                    "text": "Yes",
                    "type": "button",
                    "value": "ask_gpt"
                },
                {
                    "name": "no",
                    "text": "No",
                    "type": "button",
                    "value": "no_gpt"
                }
            ]
        }])

@app.action("ask_gpt_instead")
def message_actions(body, ack, say):
    
    ack()
    print(body)
    if body['actions'][0]['value'] == 'ask_gpt':
        say("Ok, I won't check with ChatGPT.")

    elif body['actions'][0]['value'] == 'no_gpt':
        say("Ok, I won't check with ChatGPT.")

@app.action("best_answer")
def message_actions(body, ack, say):

    #feedback collected
    print('The Best Answer is',body['actions'][0]['selected_options'][0]['value'])
    
    ack()
    say('Thanks for your feedback!')

@app.message(re.compile("(\Agpt|\AGPT)"))
def reply_in_thread(payload: dict):
    text = payload.get('text')
    query = text.lstrip('gpt ').lower()
    response = client.chat_postMessage(channel=payload.get('channel'),
        text="Generating answer...")
    # Generate a response
    completion = openai.Completion.create(engine=model_engine,
        prompt=query,
        temperature=0.5,
        max_tokens=1024,
        top_p=1,
        frequency_penalty=0,
        presence_penalty=0)

    response = client.chat_postMessage(channel=payload.get('channel'),
        text="This is a ChatGPT generated answer.")
    response = client.chat_postMessage(channel=payload.get('channel'),
        text=completion.choices[0].text)


@app.event("message")
def handle_message_events(body, logger):
    logger.info(body)

def respond_to_slack_within_3_seconds(body, ack):
    text = body.get("text")
    if text is None or len(text) == 0:
        ack("Usage: /start-process (description here)")
    else:
        ack(f"Accepted! (task: {body['text']})")

if __name__ == "__main__":
    SocketModeHandler(app, 'xapp-1-A03CNHXHAUS-3430623052035-f2906bb6adc935fbba317c7f1dbad7decd44fc8b587891797a7b1a96c81c1702').start()