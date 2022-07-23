from selenium import webdriver
from webdriver_manager.chrome import ChromeDriverManager
from webdriver_manager.core.utils import ChromeType
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
import time
import pandas as pd
import collections

chrome_service = Service(ChromeDriverManager(chrome_type=ChromeType.CHROMIUM).install())

chrome_options = Options()
options = [
    "--headless",
    "--disable-gpu",
    "--window-size=1920,1200",
    "--ignore-certificate-errors",
    "--disable-extensions",
    "--no-sandbox",
    "--disable-dev-shm-usage"
]
for option in options:
    chrome_options.add_argument(option)

driver = webdriver.Chrome(service=chrome_service, options=chrome_options)

keywords = ['sem marketing','seo marketing','social media marketing']

targeted_url = 'forbes'

ranking_list = []

for i in keywords:
    i.replace(' ','+')
    driver.get('https://www.google.com/search?q='+i+"&cr=countrysg&pws=0&num=100")
    time.sleep(10)

    lines = driver.find_element(By.XPATH,'//*[@id="rso"]').text
    print(lines)
    
    with open("Output.txt", "w") as text_file:
        text_file.write(lines)

    with open('Output.txt') as f:
        lines = f.readlines()

    #list of indexes of urls
    new = [i for i, s in enumerate(lines) if 'http' in s]

    #list of indexes of title
    indexes_titles = []
    for j in new:
        title_index = j-1
        indexes_titles.append(title_index)

    titles = []
    urls = []

    for i in new:
        titles.append(lines[i-1])
        urls.append(lines[i])

    df = pd.DataFrame(list(zip(titles,urls)),
                   columns =['title','url'],)
    df.index+=1
    df['title'] = df['title'].str.replace('\n','')
    df['url'] = df['url'].str.replace('\n','')
    df['url'] = (df['url'].str.split(' › '))
    df['url'] = df['url'].str[0]
    df.to_csv('output.csv')

    df = pd.read_csv('output.csv',index_col=0)

    try:
        ranking_list.append(((df.index[df['url'].str.contains(targeted_url)]).tolist())[0])
    except:
        ranking_list.append('not found')
    
df_kws_rankings = pd.DataFrame(list(zip(keywords,ranking_list)), columns =['keyword','position'])

df_kws_rankings.to_csv('rankings.csv')

